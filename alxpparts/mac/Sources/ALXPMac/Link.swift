// SPDX-License-Identifier: GPL-3.0-or-later
import CryptoKit
import Foundation
import Network

/// Frame types shared with the Pad (MacLink.kt).
enum Frame: UInt8 {
    case clipText = 1, clipPNG = 2
    case displayStart = 10, video = 11, displayStop = 12
    case pointer = 20, scroll = 21
    case hello = 30
}

/// Encrypted, framed connection to the Pad. See MacLink.kt for the protocol.
final class Link {
    enum State { case connecting, pairing(code: String), connected(name: String), closed }

    var onState: ((State) -> Void)?
    var onFrame: ((Frame, Data) -> Void)?

    private let connection: NWConnection
    private let queue = DispatchQueue(label: "alxp.link")
    private var key: SymmetricKey?

    init(endpoint: NWEndpoint) {
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        connection = NWConnection(to: endpoint, using: NWParameters(tls: nil, tcp: tcp))
    }

    func start() {
        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready: self?.handshake()
            case .failed, .cancelled: self?.report(.closed)
            default: break
            }
        }
        report(.connecting)
        connection.start(queue: queue)
    }

    func close() { connection.cancel() }

    // MARK: Handshake

    private static var identity: Curve25519.KeyAgreement.PrivateKey = {
        let folder = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("ALXP", isDirectory: true)
        let file = folder.appendingPathComponent("identity.key")
        if let data = try? Data(contentsOf: file),
           let key = try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: data) {
            return key
        }
        let key = Curve25519.KeyAgreement.PrivateKey()
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try? key.rawRepresentation.write(to: file, options: .completeFileProtection)
        return key
    }()

    private func handshake() {
        let identity = Link.identity
        let macPublic = identity.publicKey.rawRepresentation
        let macNonce = Data((0..<16).map { _ in UInt8.random(in: 0...255) })
        connection.send(content: Data("ALXP1".utf8) + macPublic + macNonce, completion: .idempotent)
        read(48) { [weak self] reply in
            guard let self else { return }
            let padPublic = reply.prefix(32), padNonce = reply.suffix(16)
            guard let padKey = try? Curve25519.KeyAgreement.PublicKey(rawRepresentation: padPublic),
                  let shared = try? identity.sharedSecretFromKeyAgreement(with: padKey) else {
                return self.close()
            }
            let secret = shared.withUnsafeBytes { Data($0) }
            let transcript = macPublic + padPublic + macNonce + padNonce
            self.key = SymmetricKey(data: SHA256.hash(data: Data("alxp-key".utf8) + secret + transcript))
            let digest = Data(SHA256.hash(data: Data("alxp-code".utf8) + secret + transcript))
            let number = digest.prefix(4).reduce(UInt32(0)) { $0 << 8 | UInt32($1) }
            // Shown until the Pad says hello; a known Mac gets it at once.
            self.report(.pairing(code: String(format: "%06d", number % 1_000_000)))
            self.readFrame()
        }
    }

    // MARK: Frames

    func send(_ type: Frame, _ payload: Data = Data()) {
        guard let key, let sealed = try? AES.GCM.seal(Data([type.rawValue]) + payload, using: key),
              let combined = sealed.combined else { return }
        var length = UInt32(combined.count).bigEndian
        connection.send(content: Data(bytes: &length, count: 4) + combined, completion: .idempotent)
    }

    private func readFrame() {
        read(4) { [weak self] header in
            let length = Int(header.reduce(UInt32(0)) { $0 << 8 | UInt32($1) })
            guard let self, (29...(16 << 20)).contains(length) else { self?.close(); return }
            self.read(length) { body in
                guard let key = self.key,
                      let box = try? AES.GCM.SealedBox(combined: body),
                      let plain = try? AES.GCM.open(box, using: key),
                      let type = Frame(rawValue: plain.first ?? 0) else { return self.close() }
                let payload = plain.dropFirst()
                if type == .hello {
                    self.report(.connected(name: String(decoding: payload, as: UTF8.self)))
                } else {
                    self.onFrame?(type, Data(payload))
                }
                self.readFrame()
            }
        }
    }

    private func read(_ count: Int, then: @escaping (Data) -> Void) {
        connection.receive(minimumIncompleteLength: count, maximumLength: count) { [weak self] data, _, done, error in
            guard let data, data.count == count, error == nil else {
                if done || error != nil { self?.close() }
                return
            }
            then(data)
        }
    }

    private func report(_ state: State) {
        DispatchQueue.main.async { self.onState?(state) }
    }
}

/// Finds Pads advertising _alxp._tcp on the local network.
final class Browser {
    var onChange: (([NWBrowser.Result]) -> Void)?
    private let browser = NWBrowser(for: .bonjour(type: "_alxp._tcp", domain: nil), using: .tcp)

    func start() {
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            DispatchQueue.main.async { self?.onChange?(Array(results)) }
        }
        browser.start(queue: .main)
    }
}
