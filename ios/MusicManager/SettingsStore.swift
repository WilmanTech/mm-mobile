import Foundation

/// Persistent storage for the user's "last successful sync"
/// timestamp. Phase 3.D added this so the SettingsView can show
/// "Última sincronización: hace 3 min" without keeping the
/// `SyncState` stream alive forever.
///
/// **Why a separate type and not a `@Published` on
/// `AppCoordinator`**: the `SyncState.Completed` Kotlin sealed class
/// does NOT carry a timestamp (it just has counts — see
/// `SyncCoordinator.kt`). The SettingsView previously couldn't tell
/// "synchronised 5 min ago" from "synchronised 5 hours ago" because
/// both events emitted the same `Completed` shape. We capture the
/// `Date()` on the Swift side when the first `Completed` emission
/// lands and write it to UserDefaults. The next cold launch reads
/// it back and renders it directly.
///
/// **Why UserDefaults and not the same `queue.json` file**:
/// `Settings.lastSyncDate` is a single Double (epoch seconds), so
/// `UserDefaults.set(_:forKey:)` is the idiomatic choice — no JSON
/// wrapping needed, and `Settings.app` on macOS / iOS Settings.app
/// shows it under our bundle id for debugging.
struct SettingsStore {

    /// UserDefaults key for the last successful sync. We use a
    /// `Double` (epoch seconds) rather than an ISO 8601 string so
    /// `Date(timeIntervalSince1970:)` can decode it without going
    /// through `ISO8601DateFormatter` on every Settings render.
    private static let kLastSyncEpochKey = "settings.lastSyncEpoch"

    /// Returns the persisted last-sync `Date`, or `nil` when the
    /// app has never completed a sync (fresh install, never paired,
    /// or the previous sync failed before reaching `.Completed`).
    /// Caller is responsible for the "—" fallback rendering.
    static func lastSyncDate() -> Date? {
        let raw = UserDefaults.standard.double(forKey: kLastSyncEpochKey)
        guard raw > 0 else { return nil }
        return Date(timeIntervalSince1970: raw)
    }

    /// Persist the current `Date` as the last successful sync. Called
    /// from `SettingsView` whenever a `SyncState.Completed`
    /// emission lands (see `observe()`).
    static func recordSyncCompleted() {
        UserDefaults.standard.set(
            Date().timeIntervalSince1970,
            forKey: kLastSyncEpochKey
        )
    }
}