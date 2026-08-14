import SwiftUI

/// Top-level view that switches between the pairing screen and the post-pair
/// tab bar (Phase 4.A.5 iOS parity with Android's `MusicManagerRoot`).
///
/// Layered as in the Android scaffold:
///   1. Pairing gate — `PairingScreen` until `PairingState.Paired`.
///   2. `MainTabView` once `paired` AND `LibraryEntry.Graph` is
///      available (the brief window before the graph is built still
///      shows the Phase 4.A.4 `PairedScreen` placeholder so the
///      transition isn't a flash of nothing).
///
/// **Phase 3.B addition**: the root owns the `AvPlayerEngine` as a
/// `@StateObject` so it survives across the post-pair navigation
/// (Home / Search / Library / Settings). The engine is passed down to
/// `MainTabView` which renders `NowPlayingMiniView` whenever a track
/// is loaded. While paired, any change to `coordinator.host`/`port`
/// is propagated to the engine so the next `play()` resolves the
/// stream URL against the right backend.
///
/// When `coordinator.showPreview` is true (typically set via the
/// `MM_VISUAL_REVIEW=1` launch argument in DEBUG), the root renders
/// `LibraryMockScreen` instead so designers / reviewers can land
/// directly on the library preview without going through pairing.
struct RootView: View {

    @EnvironmentObject private var coordinator: AppCoordinator
    @StateObject private var player: AvPlayerEngine

    init() {
        // `init` for a `StateObject` is fine to construct the initial
        // value via a closure — we're not observing anything yet,
        // just declaring ownership.
        _player = StateObject(
            wrappedValue: AvPlayerEngine(authStorage: AuthStorageBridge())
        )
    }

    var body: some View {
        Group {
            if coordinator.showPreview {
                LibraryMockScreen(player: player)
            } else {
                switch coordinator.phase {
                case .idle, .error:
                    PairingScreen()
                case .pending:
                    PairingScreen()
                case .paired:
                    if let graph = coordinator.libraryGraph {
                        MainTabView(graph: graph, player: player)
                            .environmentObject(coordinator)
                    } else {
                        PairedScreen(player: player)
                    }
                }
            }
        }
        .animation(.default, value: previewOrPhaseKey)
        .onChange(of: coordinator.phase) { _, newPhase in
            // Keep the player's host/port in sync with whatever the
            // coordinator committed to. The backend target is fixed
            // at the SwiftUI layer — if the user edits host/port in
            // the PairingScreen form we push the new values here so
            // the next tap-to-play resolves against the right URL.
            if case .paired = newPhase {
                player.updateBackend(host: coordinator.host, port: coordinator.port)
            }
        }
        .onChange(of: coordinator.host) { _, _ in
            // Also push backend changes while paired — the user might
            // tap "Unpair", edit the host, and re-pair without ever
            // leaving the .paired state.
            player.updateBackend(host: coordinator.host, port: coordinator.port)
        }
        .onChange(of: coordinator.port) { _, _ in
            player.updateBackend(host: coordinator.host, port: coordinator.port)
        }
    }

    /// Stable key for SwiftUI animation — using the enum case directly confuses
    /// the diffing algorithm because associated values change constantly.
    private var previewOrPhaseKey: Int {
        if coordinator.showPreview { return -1 }
        switch coordinator.phase {
        case .idle: return 0
        case .pending: return 1
        case .paired: return 2
        case .error: return 3
        }
    }
}

#Preview {
    RootView()
        .environmentObject(AppCoordinator())
}