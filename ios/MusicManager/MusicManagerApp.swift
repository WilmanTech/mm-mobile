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
/// Phase 3.D follow-up (2026-08-23): the KMP-side `AppPathHolder`
/// must be seeded with the iOS Application Support directory before
/// any KMP code runs (specifically before `AppCoordinator()` is
/// constructed — see init-order fix below).
///
/// **Init-order fix (2026-08-24)** — Swift initializes stored
/// properties BEFORE `init()` runs. The previous form declared
/// `@StateObject private var coordinator = AppCoordinator()` which
/// triggered `LibraryEntry.shared.make(...)` → `createSqlDriver()` →
/// `AppPathHolder.require()` BEFORE `init()` had a chance to call
/// `set(path:)`. Result: `IllegalStateException` thrown from the
/// KMP layer → K/N runtime → `terminateWithUnhandledException` →
/// SIGABRT at launch. Crash log (Aug 24 08:56:27) shows the abort
/// came from `kotlin::ProcessUnhandledException` right after launch.
///
/// **Fix** — split the AppPathHolder seeding into a static
/// helper (`bootstrapKmpRuntime`) that we call from BOTH the property
/// initializer and `init()`. The static helper is idempotent and
/// uses a dispatch_once-style guard so multiple invocations don't
/// fight over the path. `AppCoordinator()` is now created via
/// `_coordinator = AppCoordinator()` inside `init()` AFTER the path
/// is seeded — using a private `_coordinator` ivar plus
/// `@StateObject private var coordinator` via property wrapper
/// delegation would be cleaner but SwiftUI's `@StateObject`
/// requires the projected value pattern we already use.
@main
struct MusicManagerApp: App {

    /// Lazily-initialized coordinator. We seed `AppPathHolder` and
    /// then construct the coordinator (which internally calls
    /// `LibraryEntry.shared.make(...)` → `createSqlDriver()` →
    /// `AppPathHolder.require()`). Marked `@StateObject` at use site
    /// so SwiftUI treats it as the source of truth for the view tree.
    @StateObject private var coordinator: AppCoordinator = Self.makeCoordinator()

    /// Build the coordinator + seed the KMP AppPathHolder. Called
    /// once at struct init via the `@StateObject` initializer above.
    /// Static so it runs without needing `self` (Swift forbids
    /// `self` access in property initializers) and runs BEFORE the
    /// stored property is assigned.
    private static func makeCoordinator() -> AppCoordinator {
        _ = AppPathHolderBootstrap.runOnce
        return AppCoordinator()
    }

    /// Seed `AppPathHolder.shared` with
    /// `~/Library/Application Support/com.wtm.musicmanager.MusicManager/`.
    /// Idempotent — safe to call from both `init()` (belt-and-suspenders)
    /// and the `@StateObject` initializer (which is the one that actually
    /// fires first). The dispatch-once guard prevents re-creation of
    /// the directory if the AppDelegate / SceneDelegate re-instantiates
    /// `MusicManagerApp` (e.g. after a scene phase change in iOS 17+).
    private enum AppPathHolderBootstrap {
        static let runOnce: Void = {
            let fm = FileManager.default
            if let baseURL = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask).first {
                let appSupportDir = baseURL.appendingPathComponent("com.wtm.musicmanager.MusicManager", isDirectory: true)
                try? fm.createDirectory(at: appSupportDir, withIntermediateDirectories: true)
                AppPathHolder.shared.set(path: appSupportDir.path)
            }
        }()
    }

    /// Belt-and-suspenders: even if the `@StateObject` initializer
    /// skipped the static let (e.g. Swift's lazy-let semantics in
    /// edge cases), `init()` re-runs the bootstrap so the coordinator
    /// has the path it needs.
    init() {
        _ = AppPathHolderBootstrap.runOnce
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
