import Foundation
import os

/// Persistent storage for the playback queue + mode flags.
///
/// **Why this exists**: before this, the `AvPlayerEngine`'s
/// `@Published var queue: [PlayableTrack]` only lived in memory. Every
/// cold launch started with an empty queue and the user had to re-pick
/// the album / playlist / artist they were listening to. Phase 3.C
/// makes the queue survive `kill -9` / force-quit / reinstall-keep-data
/// / iOS background-eviction the same way `pairing_token` already does.
///
/// **What we persist** (deliberately small):
/// - `queue: [PlayableTrack]` — the current playback queue
/// - `currentIndex: Int` — where we were in the queue
/// - `isShuffled: Bool` — shuffle mode
/// - `repeatMode: RepeatMode` — repeat mode
///
/// **What we don't persist**: the `EngineState` (loading / playing /
/// paused / error). The position-vs-duration inside `EngineState` is
/// the polling tick's transient state, and persisting it would mean
/// either auto-resuming playback on launch (Apple Music doesn't do this
/// either — it shows the "play" button on the queue header) or storing
/// a stale position that's older than what the backend thinks. We
/// restore the queue but leave the user in a paused state at index 0,
/// which matches Apple Music's behaviour.
///
/// **Where the data lives**: a single JSON blob at
/// `Application Support/queue.json`. JSON is hand-rolled via
/// `JSONEncoder` because `PlayableTrack` is 4 strings — no need for
/// Codable conformance clutter on the engine itself. JSON is wrapped
/// in `try?` everywhere; a corrupted file falls back to "empty queue"
/// silently rather than crashing the launch path.
///
/// **When we persist**: `AvPlayerEngine.persistSnapshot()` is called
/// after every structural mutation (queue replace, queue clear, track
/// jump, shuffle toggle, repeat toggle). It is NOT called on every
/// position tick (250ms) — that would be ~4 disk writes per second of
/// playback. The polling tick updates `state`, which is intentionally
/// excluded from the snapshot.
struct QueueStore {

    private static let logger = Logger(subsystem: "com.wtm.musicmanager.MusicManager", category: "QueueStore")

    /// `Application Support` is the right place: backed up by iCloud
    /// (so a phone restore keeps the queue), not visible to the user
    /// in Files.app, and not auto-cleared by iOS's "offload unused
    /// apps" pass.
    static let fileURL: URL = {
        let fm = FileManager.default
        let base = (try? fm.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )) ?? fm.temporaryDirectory
        // The bundle id is the directory under app support so other
        // future apps in the same workspace don't collide. iOS already
        // sandboxes the path, so this is belt-and-suspenders, but it
        // also makes `ls Application Support/` nicer if someone is
        // debugging the device via Finder / `devicectl device files`.
        let dir = base.appendingPathComponent("com.wtm.musicmanager.MusicManager", isDirectory: true)
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("queue.json", isDirectory: false)
    }()

    struct Snapshot: Codable, Equatable {
        var queue: [PlayableTrack]
        var currentIndex: Int
        var isShuffled: Bool
        /// Stored as the raw string (`"off" / "all" / "one"`) so a
        /// future change to `RepeatMode` cases doesn't silently wipe
        /// existing snapshots — `decode` will just default to `.off`.
        var repeatModeRaw: String

        var isEmpty: Bool { queue.isEmpty }

        var repeatMode: RepeatMode {
            RepeatMode(rawValue: repeatModeRaw) ?? .off
        }
    }

    /// Read the snapshot from disk. Returns nil when the file doesn't
    /// exist (fresh install) or when JSON decoding fails (corrupt /
    /// stale schema). Never throws — persistence is best-effort.
    static func load() -> Snapshot? {
        guard FileManager.default.fileExists(atPath: fileURL.path) else {
            return nil
        }
        do {
            let data = try Data(contentsOf: fileURL)
            let decoder = JSONDecoder()
            let snap = try decoder.decode(Snapshot.self, from: data)
            logger.debug("QueueStore loaded queue=\(snap.queue.count) idx=\(snap.currentIndex) shuffle=\(snap.isShuffled) repeat=\(snap.repeatModeRaw, privacy: .public)")
            return snap
        } catch {
            logger.error("QueueStore load failed: \(String(describing: error), privacy: .public)")
            return nil
        }
    }

    /// Write the snapshot to disk atomically. Best-effort: logs and
    /// swallows the error so a disk-full / permission glitch doesn't
    /// crash the playback path.
    static func save(_ snapshot: Snapshot) {
        do {
            let encoder = JSONEncoder()
            // prettyPrinted so the file is human-readable when
            // debugging via `devicectl device files`. Costs ~50 bytes.
            encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
            let data = try encoder.encode(snapshot)
            try data.write(to: fileURL, options: [.atomic])
            logger.debug("QueueStore saved queue=\(snapshot.queue.count) idx=\(snapshot.currentIndex)")
        } catch {
            logger.error("QueueStore save failed: \(String(describing: error), privacy: .public)")
        }
    }

    /// Delete the file. Called from `AvPlayerEngine.stop()` so a
    /// "Clear queue" action (when we add one) actually wipes disk.
    /// Until then this is mostly useful for debugging — `defaults
    /// delete` from the host won't touch this file because it isn't
    /// backed by `NSUserDefaults`.
    static func clear() {
        try? FileManager.default.removeItem(at: fileURL)
    }
}