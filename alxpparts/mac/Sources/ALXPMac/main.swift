// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import Network

/// Menu bar app: finds the Pad, keeps the clipboard in sync and offers the
/// Pad as an extra display.
final class App: NSObject, NSApplicationDelegate {
    private let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
    private let browser = Browser()
    private var pads: [NWBrowser.Result] = []
    private var link: Link?
    private var clipboard: Clipboard?
    private var display: Display?
    private var state = "Looking for your Pad…"

    func applicationDidFinishLaunching(_ notification: Notification) {
        item.button?.image = NSImage(systemSymbolName: "ipad.landscape", accessibilityDescription: "ALXP")
        browser.onChange = { [weak self] results in
            self?.pads = results
            if self?.link == nil, let first = results.first { self?.connect(first.endpoint) }
            self?.rebuildMenu()
        }
        browser.start()
        rebuildMenu()
    }

    private func connect(_ endpoint: NWEndpoint) {
        let link = Link(endpoint: endpoint)
        let clipboard = Clipboard(link: link)
        let display = Display(link: link)
        link.onState = { [weak self] state in self?.linkState(state) }
        link.onFrame = { type, data in
            switch type {
            case .clipText, .clipPNG: DispatchQueue.main.async { clipboard.receive(type, data) }
            case .pointer: display.pointer(data)
            case .scroll: display.scroll(data)
            default: break
            }
        }
        self.link = link
        self.clipboard = clipboard
        self.display = display
        link.start()
    }

    private func linkState(_ new: Link.State) {
        switch new {
        case .connecting: state = "Connecting…"
        case .pairing(let code): state = "Pairing code \(code): confirm on the Pad"
        case .connected(let name):
            state = "Connected to \(name)"
            clipboard?.start()
        case .closed:
            state = "Looking for your Pad…"
            clipboard?.stop()
            if display?.isRunning == true { display?.stop() }
            link = nil
            DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in
                if self?.link == nil, let first = self?.pads.first { self?.connect(first.endpoint) }
            }
        }
        rebuildMenu()
    }

    private func rebuildMenu() {
        let menu = NSMenu()
        menu.addItem(withTitle: state, action: nil, keyEquivalent: "")
        menu.addItem(.separator())
        let connected = state.hasPrefix("Connected")
        let running = display?.isRunning == true
        let toggle = NSMenuItem(title: running ? "Stop Using Pad as Display" : "Use Pad as Display",
                                action: connected ? #selector(toggleDisplay) : nil, keyEquivalent: "")
        toggle.target = self
        menu.addItem(toggle)
        menu.addItem(.separator())
        let quit = NSMenuItem(title: "Quit ALXP", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        menu.addItem(quit)
        item.menu = menu
    }

    @objc private func toggleDisplay() {
        guard let display else { return }
        if display.isRunning {
            display.stop()
            rebuildMenu()
            return
        }
        // Moving the pointer onto the Pad needs Accessibility access.
        let options = [kAXTrustedCheckOptionPrompt.takeUnretainedValue(): true] as CFDictionary
        _ = AXIsProcessTrustedWithOptions(options)
        Task { @MainActor in
            do {
                try await display.start()
            } catch {
                let alert = NSAlert()
                alert.messageText = "Could not use the Pad as a display"
                alert.informativeText = error.localizedDescription
                alert.runModal()
            }
            rebuildMenu()
        }
    }
}

let app = NSApplication.shared
let delegate = App()
app.delegate = delegate
app.setActivationPolicy(.accessory)
app.run()
