// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit

/// Mirrors the Mac pasteboard with the Pad's clipboard: text and images.
final class Clipboard {
    private let link: Link
    private var timer: Timer?
    private var lastCount = NSPasteboard.general.changeCount

    init(link: Link) { self.link = link }

    func start() {
        timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            self?.poll()
        }
    }

    func stop() { timer?.invalidate() }

    private func poll() {
        let board = NSPasteboard.general
        guard board.changeCount != lastCount else { return }
        lastCount = board.changeCount
        if let text = board.string(forType: .string) {
            link.send(.clipText, Data(text.utf8))
        } else if let image = NSImage(pasteboard: board),
                  let tiff = image.tiffRepresentation,
                  let png = NSBitmapImageRep(data: tiff)?.representation(using: .png, properties: [:]) {
            link.send(.clipPNG, png)
        }
    }

    /// From the Pad. Our own write bumps changeCount; remember it so it is not sent back.
    func receive(_ type: Frame, _ data: Data) {
        let board = NSPasteboard.general
        board.clearContents()
        switch type {
        case .clipText: board.setString(String(decoding: data, as: UTF8.self), forType: .string)
        case .clipPNG: board.setData(data, forType: .png)
        default: return
        }
        lastCount = board.changeCount
    }
}
