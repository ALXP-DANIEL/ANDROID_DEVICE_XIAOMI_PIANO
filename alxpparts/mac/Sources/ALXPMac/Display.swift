// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import CVirtualDisplay
import CoreMedia
import ScreenCaptureKit
import VideoToolbox

/// The Pad as an extra Mac display: a virtual display, captured with
/// ScreenCaptureKit, encoded as low-latency H.264 and streamed to the Pad.
/// Touch from the Pad comes back as mouse events on that display.
final class Display: NSObject, SCStreamOutput {
    // Xiaomi Pad 8 Pro panel: 3200 x 2136 pixels, about 11.2 inches.
    static let width = 3200, height = 2136

    private let link: Link
    private var virtual: AnyObject?
    private var displayID: CGDirectDisplayID = 0
    private var stream: SCStream?
    private var encoder: VTCompressionSession?
    private let queue = DispatchQueue(label: "alxp.display")
    private var buttonDown = false

    init(link: Link) { self.link = link }

    var isRunning: Bool { virtual != nil }

    func start() async throws {
        guard let display = ALXPVirtualDisplayCreate("Xiaomi Pad", UInt32(Display.width),
                                                     UInt32(Display.height), 240, 160) else {
            throw NSError(domain: "ALXP", code: 1, userInfo: [NSLocalizedDescriptionKey:
                "macOS refused to create a virtual display"])
        }
        virtual = display as AnyObject
        displayID = ALXPVirtualDisplayID(display)
        try makeEncoder()

        var header = Data()
        header.append(contentsOf: withUnsafeBytes(of: UInt16(Display.width).bigEndian) { Array($0) })
        header.append(contentsOf: withUnsafeBytes(of: UInt16(Display.height).bigEndian) { Array($0) })
        link.send(.displayStart, header)

        // The new display takes a moment to appear to ScreenCaptureKit.
        var target: SCDisplay?
        for _ in 0..<20 where target == nil {
            try await Task.sleep(nanoseconds: 150_000_000)
            target = try await SCShareableContent.current.displays.first { $0.displayID == displayID }
        }
        guard let target else { throw NSError(domain: "ALXP", code: 2) }

        let config = SCStreamConfiguration()
        config.width = Display.width
        config.height = Display.height
        config.minimumFrameInterval = CMTime(value: 1, timescale: 60)
        config.pixelFormat = kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
        config.queueDepth = 3
        config.showsCursor = true
        let stream = SCStream(filter: SCContentFilter(display: target, excludingWindows: []),
                              configuration: config, delegate: nil)
        try stream.addStreamOutput(self, type: .screen, sampleHandlerQueue: queue)
        try await stream.startCapture()
        self.stream = stream
    }

    func stop() {
        stream?.stopCapture { _ in }
        stream = nil
        if let encoder { VTCompressionSessionInvalidate(encoder) }
        encoder = nil
        virtual = nil
        link.send(.displayStop)
    }

    // MARK: Encoding

    private func makeEncoder() throws {
        var session: VTCompressionSession?
        let status = VTCompressionSessionCreate(
            allocator: nil, width: Int32(Display.width), height: Int32(Display.height),
            codecType: kCMVideoCodecType_H264,
            encoderSpecification: [kVTVideoEncoderSpecification_EnableLowLatencyRateControl: true] as CFDictionary,
            imageBufferAttributes: nil, compressedDataAllocator: nil,
            outputCallback: nil, refcon: nil, compressionSessionOut: &session)
        guard status == noErr, let session else { throw NSError(domain: "ALXP", code: Int(status)) }
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_RealTime, value: kCFBooleanTrue)
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_AllowFrameReordering, value: kCFBooleanFalse)
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_ProfileLevel,
                             value: kVTProfileLevel_H264_High_AutoLevel)
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_AverageBitRate, value: 30_000_000 as CFNumber)
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_MaxKeyFrameInterval, value: 120 as CFNumber)
        VTCompressionSessionPrepareToEncodeFrames(session)
        encoder = session
    }

    func stream(_ stream: SCStream, didOutputSampleBuffer buffer: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .screen, let encoder, let image = CMSampleBufferGetImageBuffer(buffer) else { return }
        VTCompressionSessionEncodeFrame(encoder, imageBuffer: image,
                                        presentationTimeStamp: CMSampleBufferGetPresentationTimeStamp(buffer),
                                        duration: .invalid, frameProperties: nil, infoFlagsOut: nil) {
            [weak self] status, _, sample in
            guard status == noErr, let sample, let self else { return }
            self.link.send(.video, Display.annexB(sample))
        }
    }

    /// VideoToolbox gives length-prefixed NAL units; Android wants start codes,
    /// with SPS and PPS in front of every key frame.
    private static func annexB(_ sample: CMSampleBuffer) -> Data {
        let startCode = Data([0, 0, 0, 1])
        var out = Data()
        let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: false)
            as? [[CFString: Any]]
        let keyFrame = !(attachments?.first?[kCMSampleAttachmentKey_NotSync] as? Bool ?? false)
        if keyFrame, let format = CMSampleBufferGetFormatDescription(sample) {
            for index in 0..<2 {
                var pointer: UnsafePointer<UInt8>?
                var size = 0
                if CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
                    format, parameterSetIndex: index, parameterSetPointerOut: &pointer,
                    parameterSetSizeOut: &size, parameterSetCountOut: nil,
                    nalUnitHeaderLengthOut: nil) == noErr, let pointer {
                    out += startCode + Data(bytes: pointer, count: size)
                }
            }
        }
        guard let block = CMSampleBufferGetDataBuffer(sample) else { return out }
        var length = 0
        var pointer: UnsafeMutablePointer<CChar>?
        CMBlockBufferGetDataPointer(block, atOffset: 0, lengthAtOffsetOut: nil,
                                    totalLengthOut: &length, dataPointerOut: &pointer)
        guard let pointer else { return out }
        let bytes = UnsafeRawPointer(pointer)
        var offset = 0
        while offset + 4 <= length {
            let nalLength = Int(bytes.load(fromByteOffset: offset, as: UInt32.self).bigEndian)
            offset += 4
            out += startCode + Data(bytes: bytes + offset, count: min(nalLength, length - offset))
            offset += nalLength
        }
        return out
    }

    // MARK: Input from the Pad

    /// action: 0 hover, 1 down, 2 drag, 3 up. Coordinates are 0...1 of the Pad screen.
    func pointer(_ data: Data) {
        guard data.count == 9, isRunning else { return }
        let action = data[data.startIndex]
        let x = Display.float(data, 1), y = Display.float(data, 5)
        let bounds = CGDisplayBounds(displayID)
        let point = CGPoint(x: bounds.minX + CGFloat(x) * bounds.width,
                            y: bounds.minY + CGFloat(y) * bounds.height)
        let type: CGEventType
        switch action {
        case 1: type = .leftMouseDown; buttonDown = true
        case 2: type = buttonDown ? .leftMouseDragged : .mouseMoved
        case 3: type = .leftMouseUp; buttonDown = false
        default: type = .mouseMoved
        }
        CGEvent(mouseEventSource: nil, mouseType: type, mouseCursorPosition: point,
                mouseButton: .left)?.post(tap: .cghidEventTap)
    }

    func scroll(_ data: Data) {
        guard data.count == 9 else { return }
        let dx = Display.float(data, 1), dy = Display.float(data, 5)
        CGEvent(scrollWheelEvent2Source: nil, units: .pixel, wheelCount: 2,
                wheel1: Int32(dy), wheel2: Int32(dx), wheel3: 0)?.post(tap: .cghidEventTap)
    }

    private static func float(_ data: Data, _ offset: Int) -> Float {
        let start = data.startIndex + offset
        let bits = data[start..<start + 4].reduce(UInt32(0)) { $0 << 8 | UInt32($1) }
        return Float(bitPattern: bits)
    }
}
