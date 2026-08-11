import SwiftUI

/// MusicManager iOS app entry point.
///
/// Phase 4.A.2 applies the brand identity:
/// - `.preferredColorScheme(.dark)` so the whole app stays in the
///   dark-mode palette regardless of the device setting.
/// - `.tint(MusicManagerTheme.accent)` so SwiftUI default buttons,
///   toggles, and selection highlights pick up the brand yellow
///   automatically.
@main
struct MusicManagerApp: App {

    @StateObject private var coordinator = AppCoordinator()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(coordinator)
                .preferredColorScheme(.dark)
                .tint(MusicManagerTheme.accent)
                .onOpenURL { url in
                    coordinator.handleDeepLink(url)
                }
        }
    }
}
