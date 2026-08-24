import SwiftUI
import MusicManagerShared

/// MusicManager iOS app entry point.
///
/// Phase 4.A.2 applies the brand identity:
/// - `.preferredColorScheme(.dark)` so the whole app stays in the
///   dark-mode palette regardless of the device setting.
/// - `.tint(MusicManagerTheme.accent)` so SwiftUI default buttons,
///   toggles, and selection highlights pick up the brand yellow
///   automatically.
///
/// Phase 3.D follow-up (2026-08-23): `init()` now seeds the Kotlin
/// `AppPathHolder` with the iOS Application Support directory before
/// any KMP code runs. Without this, the first call to
/// `MusicManagerDatabaseFactory.create()` would throw
/// `IllegalStateException: AppPathHolder not initialised` because
/// the data layer needs an absolute file path for the SQLDelight
/// driver (the previous bare-name `"musicmanager.db"` form wrote
/// to the process CWD, which on iOS is `/` and silently failed to
/// persist between launches — the root cause of the "tap returns
/// 404 for stale track id" bug the user reported on 2026-08-23).
@main
struct MusicManagerApp: App {

    @StateObject private var coordinator = AppCoordinator()

    init() {
        // Seed the KMP-side AppPathHolder with
        // `~/Library/Application Support/com.wtm.musicmanager.MusicManager/`
        // before anything else in the app touches the data layer.
        // The path is created with intermediate directories so a
        // fresh install doesn't crash on first launch.
        let fm = FileManager.default
        if let baseURL = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask).first {
            let appSupportDir = baseURL.appendingPathComponent("com.wtm.musicmanager.MusicManager", isDirectory: true)
            try? fm.createDirectory(at: appSupportDir, withIntermediateDirectories: true)
            AppPathHolder.shared.set(path: appSupportDir.path)
        }
    }

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
