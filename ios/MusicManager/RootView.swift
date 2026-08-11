import SwiftUI

/// Top-level view that switches between the pairing screen and the post-pair
/// "you are connected" placeholder. Real library / search / now-playing tabs
/// come in later phases (Phase 1+ iOS parity); for the MVP smoke we just need
/// to prove the pairing round-trip works end-to-end.
struct RootView: View {

    @EnvironmentObject private var coordinator: AppCoordinator

    var body: some View {
        Group {
            switch coordinator.phase {
            case .idle, .error:
                PairingScreen()
            case .pending:
                PairingScreen()
            case .paired:
                PairedScreen()
            }
        }
        .animation(.default, value: phaseKey)
    }

    /// Stable key for SwiftUI animation — using the enum case directly confuses
    /// the diffing algorithm because associated values change constantly.
    private var phaseKey: Int {
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
