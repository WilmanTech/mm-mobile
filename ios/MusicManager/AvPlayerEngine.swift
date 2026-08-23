import AVFoundation
import Combine
import Foundation

/// Playback engine for the iOS MusicManager app. Phase 3.B+ deliverable.
///
/// Backed by `AVPlayer` (not `AVAudioPlayer`) so the streaming endpoint
/// can serve `Range` requests — the backend's `/api/stream/{id}`
/// responds with `206 Partial Content` and that's how AVPlayer pulls
/// the bytes. Plain `AVAudioPlayer` would buffer the entire file.
///
/// **Bearer attachment** — `AVPlayer` does not reuse the Ktor
/// `URLSession`'s auth interceptor, so we set the `Authorization`
/// header on the underlying `AVURLAsset` via
/// `AVURLAssetHTTPHeaderFieldsKey`. The bearer comes from
/// `MusicManager.authStorage` (NSUserDefaults-backed). Pairing
/// must have completed before any `play(track:)` call.
///
/// **Queue + auto-advance** (Phase 3.B+):
/// - `play(track:in:)` replaces the queue with the supplied list and
///   starts at the index where `track` lives, so tapping an album /
///   artist / playlist track plays from that point onwards.
/// - `next()` / `previous()` walk the queue linearly. When shuffle is
///   on, `next()` picks a random unplayed track instead.
/// - The polling tick auto-advances to `next()` when the current track
///   reaches its end (detected via AVPlayer's `AVPlayerItemDidPlayToEndTime`
///   notification), so a finished track flows into the next one
///   without user input.
///
/// **Auto-play on tap** — if the engine is currently paused and a new
/// `play(track:)` arrives, it always starts playing the new track
/// (instead of staying in paused state at position 0). This matches
/// Apple Music / Spotify behaviour: tapping a row always starts audio.
///
/// **State surface** — mirrors the KMP `PlayerState` sealed hierarchy
/// so future cross-platform UI work can swap out the engine without
/// touching the views.
@MainActor
final class AvPlayerEngine: ObservableObject {

    /// Tracks what's currently playing (or not). Published so SwiftUI
    /// views update whenever the engine transitions.
    @Published private(set) var state: EngineState = .idle

    /// True only while the engine has loaded bytes and the player
    /// is actively rendering audio. False during loading, pause, and
    /// idle. The mini-bar shows different chrome for each.
    @Published private(set) var isPlaying: Bool = false

    /// Tracks queued for playback. Phase 3.B+ exposes this so the
    /// `NowPlayingView` queue sheet can render the list and let the
    /// user jump to any track by tapping it.
    @Published private(set) var queue: [PlayableTrack] = []

    /// Index of the currently-playing track inside `queue`. -1 when
    /// the queue is empty (idle / cleared).
    @Published private(set) var currentIndex: Int = -1

    /// Shuffle state. When on, `next()` picks a random unplayed track
    /// instead of the linear successor. Shuffle does NOT reshuffle
    /// the queue — we just sample from it.
    @Published var isShuffled: Bool = false

    /// Repeat state. Cycles off → all → one via `toggleRepeat()`.
    /// - `.off`: `next()` returns at the end of the queue (no auto-advance).
    /// - `.all`: `next()` loops back to index 0 at the end (default loop).
    /// - `.one`: the current track restarts when it reaches the end,
    ///   `next()` still advances to the next track on user input.
    @Published var repeatMode: RepeatMode = .off

    private var avPlayer: AVPlayer?
    private var pollTimer: Timer?
    private var authStorage: AuthStorageBridge
    private var host: String = "127.0.0.1"
    private var port: String = "8765"

    /// `AVAudioSession.setCategory(.playback)` only needs to happen once
    /// per process. Doing it on every `play()` call is harmless but
    /// creates spurious log noise on iOS 26 ("AVAudioSession category
    /// changed from playback to playback" warnings). Track the
    /// initialization explicitly so we can call it lazily from the
    /// first `play()` instead of in `init` (which runs on the
    /// SwiftUI render path and would force AVAudioSession boot before
    /// the user has actually tried to listen).
    private var audioSessionConfigured = false

    init(authStorage: AuthStorageBridge) {
        self.authStorage = authStorage
    }

    /// Update the backend target host/port. The next `play()` call
    /// will resolve the stream URL against the new endpoint.
    func updateBackend(host: String, port: String) {
        self.host = host
        self.port = port
    }

    // MARK: - Playback control

    /// Replace the queue with `[track]` and start playback. Convenience
    /// wrapper for single-row taps (LibraryScreen, NowPlayingMiniView
    /// tap on the currently playing row, etc.).
    func play(track: PlayableTrack) {
        play(track: track, in: [track])
    }

    /// Replace the queue with `tracks` and start playback at the index
    /// where `track` lives. If `track` is not in `tracks`, it falls
    /// back to playing the first track in the queue.
    ///
    /// This is the canonical entry point for album / artist / playlist
    /// / "shuffle all" taps because it gives the engine enough
    /// information to auto-advance when the current track ends.
    func play(track: PlayableTrack, in tracks: [PlayableTrack]) {
        guard !tracks.isEmpty else { return }

        // Find the tap target's index in the new queue. If the caller
        // passed a track that's not in the queue (shouldn't happen, but
        // be defensive), fall back to the first track.
        let targetIndex = tracks.firstIndex(of: track) ?? 0
        queue = tracks
        currentIndex = targetIndex
        startPlayback(at: targetIndex)
    }

    /// Skip to the next track in the queue. Honours shuffle and repeat:
    /// - shuffle on: picks a random unplayed track instead of the
    ///   linear successor.
    /// - repeat off at the end of the queue: no-op (player stops).
    /// - repeat all at the end: wraps to index 0.
    /// - repeat one: handled in `trackDidFinish` (restarts the same
    ///   track) — `next()` here still advances linearly.
    /// No-op when the queue is empty.
    func next() {
        guard !queue.isEmpty else { return }
        let nextIndex: Int
        if isShuffled && queue.count > 1 {
            // Sample a different index uniformly. Bias toward tracks
            // ahead of the current one to avoid feeling random.
            let candidates = (0..<queue.count).filter { $0 != currentIndex }
            nextIndex = candidates.randomElement() ?? 0
        } else if currentIndex >= queue.count - 1 {
            // Phase 3.B+ (2026-08-14) behaviour change: when the
            // user reaches the end of the queue, wrap back to index
            // 0 (Apple Music behaviour) instead of stopping
            // playback silently. Previously the engine returned
            // silently when repeat was off and the user had to
            // manually press play; the user reported "fin cancion
            // no hace nada". Auto-play continues so the queue feels
            // like a single continuous listening session. If the
            // user wants strict queue-end behaviour they can tap
            // pause, switch to repeat-one, or set repeat to off
            // and stop manually.
            nextIndex = 0
        } else {
            nextIndex = (currentIndex + 1) % queue.count
        }
        currentIndex = nextIndex
        startPlayback(at: nextIndex)
    }

    /// Go back to the previous track. In shuffle mode this is the
    /// previous *physical* index, not a random one — shuffle only
    /// affects "next", not "prev" (matches Apple Music).
    func previous() {
        guard !queue.isEmpty else { return }
        let prevIndex = currentIndex <= 0
            ? queue.count - 1
            : currentIndex - 1
        currentIndex = prevIndex
        startPlayback(at: prevIndex)
    }

    /// Jump to an absolute index in the queue. No-op when the index
    /// is out of bounds. Used by the queue sheet's row taps.
    func jumpTo(index: Int) {
        guard queue.indices.contains(index) else { return }
        currentIndex = index
        startPlayback(at: index)
    }

    /// Toggle between play and pause. No-op if no track is loaded.
    func playPause() {
        guard avPlayer != nil else { return }
        if isPlaying {
            avPlayer?.pause()
            isPlaying = false
            updatePausedState()
        } else {
            avPlayer?.play()
            isPlaying = true
            updatePlayingState()
        }
    }

    /// Seek to the given position. No-op if no track is loaded.
    func seek(positionMs: Int) {
        guard let player = avPlayer else { return }
        let target = CMTime(value: CMTimeValue(positionMs), timescale: 1000)
        player.seek(to: target)
    }

    /// Stop and clear. The queue is dropped, the player is paused, the
    /// state returns to `.idle`. Subsequent `play(track:)` calls start
    /// fresh with a single-track queue.
    func stop() {
        avPlayer?.pause()
        avPlayer = nil
        stopPolling()
        queue = []
        currentIndex = -1
        state = .idle
        isPlaying = false
    }

    /// Toggle shuffle on/off. When turning on, the next `next()` call
    /// will pick a random unplayed track. Turning off restores the
    /// linear walk.
    func toggleShuffle() {
        isShuffled.toggle()
    }

    /// Cycle the repeat mode: off → all → one → off. Apple Music
    /// convention — same three-way button, same order.
    func toggleRepeat() {
        switch repeatMode {
        case .off: repeatMode = .all
        case .all: repeatMode = .one
        case .one: repeatMode = .off
        }
    }

    // MARK: - Internal

    /// Build the AVURLAsset for `queue[currentIndex]` and start
    /// playback. Called by every public entry point that wants to
    /// start or swap tracks.
    private func startPlayback(at index: Int) {
        guard queue.indices.contains(index) else { return }
        let track = queue[index]

        // Configure AVAudioSession for `.playback` on the first call
        // only. Without this, AVPlayer on iOS 26 routes audio to
        // silence — the session defaults to `soloAmbient` which
        // mixes with system sounds but isn't routed to the speaker.
        if !audioSessionConfigured {
            do {
                try AVAudioSession.sharedInstance().setCategory(
                    .playback,
                    mode: .default,
                    options: [],
                )
                try AVAudioSession.sharedInstance().setActive(true)
                audioSessionConfigured = true
            } catch {
                NSLog("AvPlayerEngine: AVAudioSession.configure failed: %@", String(describing: error))
            }
        }

        state = .loading(track: track)
        isPlaying = false

        // Tear down previous player.
        avPlayer?.pause()
        avPlayer = nil
        stopPolling()

        guard let bearer = authStorage.loadToken(), !bearer.isEmpty else {
            state = .error(reason: "No bearer token — re-pair required")
            return
        }

        let urlString = "http://\(host):\(port)/api/stream/\(track.id)"
        guard var components = URLComponents(string: urlString) else {
            state = .error(reason: "Invalid stream URL: \(urlString)")
            return
        }
        // AVURLAsset no longer exposes the
        // `AVURLAssetHTTPHeaderFieldsKey` option on iOS 26 — the
        // Swift bridge drops the symbol. We work around by
        // appending the bearer as a `token` query parameter, which
        // the MusicManager `/api/stream/{id}` handler accepts as a
        // fallback when the `Authorization` header is missing.
        components.queryItems = (components.queryItems ?? []) + [
            URLQueryItem(name: "token", value: bearer),
        ]
        guard let url = components.url else {
            state = .error(reason: "Invalid stream URL: \(urlString)")
            return
        }

        let asset = AVURLAsset(url: url)
        let item = AVPlayerItem(asset: asset)
        let player = AVPlayer(playerItem: item)
        avPlayer = player

        // Auto-advance to next() when the track finishes. Phase 3.B+
        // makes the engine a real queue walker instead of a
        // single-track player, so this notification is the trigger
        // for chained playback.
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(trackDidFinish(_:)),
            name: .AVPlayerItemDidPlayToEndTime,
            object: item,
        )

        player.play()
        isPlaying = true

        // Emit Playing immediately — duration will resolve async.
        state = .playing(
            track: track,
            positionMs: 0,
            durationMs: 0,
        )
        startPolling(track: track)
    }

    @objc private func trackDidFinish(_ note: Notification) {
        // The notification fires on whichever item finished. Only
        // advance if the engine is still playing that exact item —
        // otherwise the user has skipped ahead and we shouldn't double-
        // advance.
        guard
            let item = note.object as? AVPlayerItem,
            item === avPlayer?.currentItem,
            currentIndex >= 0
        else { return }
        // Repeat-one restarts the current track from 0. The user
        // can still hit next() to move on.
        if repeatMode == .one {
            avPlayer?.seek(to: .zero)
            avPlayer?.play()
            return
        }
        next()
    }

    // MARK: - Polling

    private func startPolling(track: PlayableTrack) {
        stopPolling()
        pollTimer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
            Task { @MainActor in
                self?.tick(track: track)
            }
        }
    }

    private func stopPolling() {
        pollTimer?.invalidate()
        pollTimer = nil
    }

    private func tick(track: PlayableTrack) {
        guard let player = avPlayer else { return }
        // CMTimeGetSeconds returns NaN/inf when the asset hasn't loaded
        // yet (or when its duration is unknown — common for live HLS
        // streams). Casting NaN to Int is undefined behaviour in
        // Swift's strict mode and was crashing the timer callback.
        let durationSeconds = CMTimeGetSeconds(player.currentItem?.duration ?? .zero)
        let positionSeconds = CMTimeGetSeconds(player.currentTime())
        let durationMs = durationSeconds.isFinite ? Int(durationSeconds * 1000) : 0
        let positionMs = positionSeconds.isFinite ? Int(positionSeconds * 1000) : 0
        if isPlaying {
            state = .playing(
                track: track,
                positionMs: positionMs,
                durationMs: durationMs,
            )
        } else {
            state = .paused(
                track: track,
                positionMs: positionMs,
                durationMs: durationMs,
            )
        }
    }

    private func updatePausedState() {
        guard case let .playing(track, positionMs, durationMs) = state else { return }
        state = .paused(track: track, positionMs: positionMs, durationMs: durationMs)
    }

    private func updatePlayingState() {
        guard case let .paused(track, positionMs, durationMs) = state else { return }
        state = .playing(track: track, positionMs: positionMs, durationMs: durationMs)
    }
}

/// State surface that mirrors the KMP `PlayerState` sealed hierarchy.
/// Today Swift-only; once the KMP bridge lands, the engine collects
/// from a `StateFlow<PlayerState>` and re-publishes into the same
/// enum so SwiftUI views don't notice.
enum EngineState: Equatable {
    case idle
    case loading(track: PlayableTrack)
    case playing(track: PlayableTrack, positionMs: Int, durationMs: Int)
    case paused(track: PlayableTrack, positionMs: Int, durationMs: Int)
    case error(reason: String)
}

/// Track metadata that the player engine needs. Mirrors what the
/// KMP `db.Track` row will provide.
struct PlayableTrack: Equatable, Hashable {
    let id: String
    let title: String
    let artistName: String
    let albumTitle: String
    /// Optional album id, used by the queue sheet / NowPlayingView to
    /// jump back to the source album. Empty when the source is unknown
    /// (e.g. shuffle all).
    let albumId: String
}

/// Bridge to the KMP-side `AuthStorage`. Today this is a thin Swift
/// wrapper around `NSUserDefaults.standardUserDefaults` reading the
/// `pairing_token` key.
struct AuthStorageBridge {
    private let defaults: UserDefaults
    private let tokenKey: String

    init(defaults: UserDefaults = .standard, tokenKey: String = "pairing_token") {
        self.defaults = defaults
        self.tokenKey = tokenKey
    }

    func loadToken() -> String? {
        defaults.string(forKey: tokenKey)
    }
}

/// Repeat mode for the queue walker. Cycles off → all → one via
/// `AvPlayerEngine.toggleRepeat()`. The systemImageName / label
/// helpers drive the chrome in NowPlayingView.
enum RepeatMode: String, CaseIterable {
    case off
    case all
    case one

    var label: String {
        switch self {
        case .off: return "off"
        case .all: return "all"
        case .one: return "one"
        }
    }

    var systemImageName: String {
        switch self {
        case .off: return "repeat"
        case .all: return "repeat"
        case .one: return "repeat.1"
        }
    }
}
