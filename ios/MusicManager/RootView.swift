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
/// When `coordinator.showPreview` is true (typically set via the
/// `MM_VISUAL_REVIEW=1` launch argument in DEBUG), the root renders
/// `LibraryMockScreen` instead so designers / reviewers can land
/// directly on the library preview without going through pairing.
struct RootView: View {

    @EnvironmentObject private var coordinator: AppCoordinator

    var body: some View {
        Group {
            if coordinator.showPreview {
                LibraryMockScreen()
            } else {
                switch coordinator.phase {
                case .idle, .error:
                    PairingScreen()
                case .pending:
                    PairingScreen()
                case .paired:
                    if let graph = coordinator.libraryGraph {
                        MainTabView(graph: graph)
                    } else {
                        PairedScreen()
                    }
                }
            }
        }
        .animation(.default, value: previewOrPhaseKey)
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
