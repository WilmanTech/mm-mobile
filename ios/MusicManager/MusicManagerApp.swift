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
/// **Init-order fix (2026-08-24, v2)** — the first version of
/// this fix (commit `6595f3e`) used a static let inside the
/// `MusicManagerApp` struct. That worked in the iOS Simulator
/// but **failed on the iPhone 11 device**: the `AppPathHolder`
/// stayed uninitialised and `LibraryEntry.makeOrNull` returned
/// nil → red "Library init failed" banner in the UI. The user
/// reported "app path holder not initialized" verbatim.
///
/// **Why the static let didn't fire on device** — Swift's
/// dispatch-once semantics for static lets inside a generic
/// struct are tied to the struct's metadata instantiation, which
/// on iOS device (with `-O` codegen) gets lazy-evaluated until
/// the first member is touched. The `@StateObject private var
/// coordinator: AppCoordinator = Self.makeCoordinator()` initializer
/// calls `makeCoordinator()` which references the static let, but
/// at that point Swift has already started initialising the
/// struct's storage. The K/N `AppPathHolder` ended up in the
/// "uninitialised" state when `LibraryEntry.makeOrNull` ran
/// because the static let's body never executed on device.
///
/// **v2 fix** — move the bootstrap to a **file-scope** static
/// let (`_kmpBootstrap`) that is *not* a member of any struct. Swift
/// evaluates file-scope static lets eagerly at module load, before
/// any `@main` type's init runs. The `_` prefix marks it as
/// "implementation detail" (not part of the API). We also touch it
/// from `init()` as a belt-and-suspenders fallback for any future
/// Swift version that decides to lazy-evaluate file-scope state.
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
        _ = _kmpBootstrap
        return AppCoordinator()
    }

    /// Belt-and-suspenders: even if the static let inside the
    /// struct is somehow not invoked (e.g. Swift's lazy semantics
    /// on device), `init()` re-references the file-scope static
    /// let so the coordinator has the path it needs.
    init() {
        _ = _kmpBootstrap
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

/// File-scope bootstrap. Marked `_` (private to file) so the
/// MusicManagerApp struct can reference it but the rest of the
/// module can't accidentally reach it. Evaluated by Swift at
/// module load time, **before** any `@main` type's storage is
/// initialised, which is the timing we need: this guarantees
/// `AppPathHolder.shared.set(path: ...)` has run before
/// `AppCoordinator()` tries to construct a `LibraryEntry.Graph`.
private let _kmpBootstrap: Void = {
    let fm = FileManager.default
    if let baseURL = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask).first {
        let appSupportDir = baseURL.appendingPathComponent("com.wtm.musicmanager.MusicManager", isDirectory: true)
        try? fm.createDirectory(at: appSupportDir, withIntermediateDirectories: true)
        AppPathHolder.shared.set(path: appSupportDir.path)
    }
}()
