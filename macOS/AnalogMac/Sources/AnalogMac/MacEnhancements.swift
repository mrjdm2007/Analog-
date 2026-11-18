import AppKit
import AVFoundation
import CoreAudio
import AnalogCore
import SwiftUI

@MainActor
final class OutputDeviceMonitor: ObservableObject {
    @Published private(set) var deviceName = "Detecting output…"
    private var timer: Timer?

    init() {
        refresh()
        timer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.refresh() }
        }
    }

    deinit { timer?.invalidate() }

    func refresh() {
        var address = AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyDefaultOutputDevice,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        var device = AudioDeviceID(0)
        var size = UInt32(MemoryLayout<AudioDeviceID>.size)
        guard AudioObjectGetPropertyData(
            AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, &size, &device
        ) == noErr, device != 0 else {
            deviceName = "No output device"
            return
        }

        var nameAddress = AudioObjectPropertyAddress(
            mSelector: kAudioObjectPropertyName,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        var name: CFString = "" as CFString
        size = UInt32(MemoryLayout<CFString>.size)
        guard AudioObjectGetPropertyData(device, &nameAddress, 0, nil, &size, &name) == noErr else {
            deviceName = "Unknown output device"
            return
        }
        let resolved = (name as String).trimmingCharacters(in: .whitespacesAndNewlines)
        deviceName = resolved.isEmpty ? "Unknown output device" : resolved
    }
}

struct MiniPlayerView: View {
    @EnvironmentObject private var player: AnalogPlayer
    @Environment(\.openWindow) private var openWindow
    @AppStorage("analogMiniPlayerAlwaysOnTop") private var alwaysOnTop = false

    var body: some View {
        HStack(spacing: 12) {
            Group {
                if let path = player.currentTrack?.artworkPath, let image = NSImage(contentsOfFile: path) {
                    Image(nsImage: image).resizable().aspectRatio(contentMode: .fill)
                } else {
                    ZStack {
                        RoundedRectangle(cornerRadius: 9).fill(Color.white.opacity(0.07))
                        Image(systemName: "waveform").font(.system(size: 22)).foregroundStyle(Color(red: 0.95, green: 0.69, blue: 0.36))
                    }
                }
            }
            .frame(width: 58, height: 58).clipShape(RoundedRectangle(cornerRadius: 9))

            VStack(alignment: .leading, spacing: 4) {
                Text(player.currentTrack?.title ?? "Nothing playing")
                    .font(.system(size: 12, weight: .semibold)).lineLimit(1)
                Text(player.currentTrack?.artist ?? "Choose a track in your library")
                    .font(.system(size: 10)).foregroundStyle(.secondary).lineLimit(1)
                HStack(spacing: 17) {
                    Button { player.previous() } label: { Image(systemName: "backward.fill") }
                    Button { player.togglePlayback() } label: {
                        Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                            .font(.system(size: 15, weight: .bold))
                    }
                    Button { player.next() } label: { Image(systemName: "forward.fill") }
                }
                .buttonStyle(.plain).font(.system(size: 12))
            }
            Spacer(minLength: 2)
            VStack(spacing: 10) {
                Button { openWindow(id: "main-window") } label: {
                    Image(systemName: "arrow.up.left.and.arrow.down.right")
                }.help("Open Analog")
                Button { alwaysOnTop.toggle() } label: {
                    Image(systemName: alwaysOnTop ? "pin.fill" : "pin")
                        .foregroundStyle(alwaysOnTop ? Color.orange : Color.secondary)
                }.help("Keep mini-player above other windows")
            }.buttonStyle(.plain).font(.system(size: 11))
        }
        .padding(12)
        .frame(width: 360, height: 84)
        .background(.ultraThinMaterial)
        .background(WindowLevelConfigurator(isFloating: alwaysOnTop))
        .preferredColorScheme(.dark)
    }
}

private final class WindowDragArea: NSView {
    override var mouseDownCanMoveWindow: Bool { true }
}

private struct WindowLevelConfigurator: NSViewRepresentable {
    let isFloating: Bool
    func makeNSView(context: Context) -> NSView {
        let view = WindowDragArea()
        DispatchQueue.main.async { view.window?.level = isFloating ? .floating : .normal }
        return view
    }
    func updateNSView(_ view: NSView, context: Context) {
        DispatchQueue.main.async { view.window?.level = isFloating ? .floating : .normal }
    }
}

struct SpacebarShortcutMonitor: NSViewRepresentable {
    let onSpace: () -> Void

    func makeNSView(context: Context) -> NSView {
        let view = NSView()
        context.coordinator.install(onSpace: onSpace)
        return view
    }
    func updateNSView(_ view: NSView, context: Context) {
        context.coordinator.onSpace = onSpace
    }
    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator {
        var monitor: Any?
        var onSpace: (() -> Void)?
        func install(onSpace: @escaping () -> Void) {
            self.onSpace = onSpace
            guard monitor == nil else { return }
            monitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
                guard event.keyCode == 49,
                      event.modifierFlags.intersection(.deviceIndependentFlagsMask).isEmpty,
                      let window = NSApp.keyWindow else { return event }
                let responder = window.firstResponder
                if responder is NSTextView || responder is NSControl {
                    return event
                }
                self?.onSpace?()
                return nil
            }
        }
        deinit {
            if let monitor { NSEvent.removeMonitor(monitor) }
        }
    }
}

struct MainWindowVisibilityProbe: NSViewRepresentable {
    let onChange: (Bool) -> Void

    func makeNSView(context: Context) -> NSView {
        let view = VisibilityView()
        view.onChange = onChange
        return view
    }
    func updateNSView(_ view: NSView, context: Context) {
        (view as? VisibilityView)?.onChange = onChange
        (view as? VisibilityView)?.report()
    }

    final class VisibilityView: NSView {
        var onChange: ((Bool) -> Void)?
        private var observers: [NSObjectProtocol] = []
        private var lastReportedVisibility: Bool?

        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            for observer in observers { NotificationCenter.default.removeObserver(observer) }
            observers.removeAll()
            let names: [Notification.Name] = [
                NSWindow.didMiniaturizeNotification,
                NSWindow.didDeminiaturizeNotification,
                NSWindow.didBecomeKeyNotification,
                NSWindow.didResignKeyNotification,
                NSWindow.didChangeOcclusionStateNotification,
                NSWindow.didChangeScreenNotification,
                NSApplication.didBecomeActiveNotification,
                NSApplication.didResignActiveNotification
            ]
            for name in names {
                observers.append(NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                    self?.report()
                })
            }
            report()
        }

        func report() {
            let visible = window.map {
                $0.isVisible && !$0.isMiniaturized && $0.occlusionState.contains(.visible)
            } ?? false
            guard visible != lastReportedVisibility else { return }
            lastReportedVisibility = visible
            DispatchQueue.main.async { [weak self] in
                self?.onChange?(visible)
            }
        }

        deinit { for observer in observers { NotificationCenter.default.removeObserver(observer) } }
    }
}
