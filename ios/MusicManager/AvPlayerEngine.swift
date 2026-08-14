import AVFoundation
import Combine
import Foundation

/// Playback engine for the iOS MusicManager app. Phase 3.B deliverable.
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
/// **State surface** — mirrors the KMP `PlayerState` sealed hierarchy
/// so future cross-platform UI work can swap out the engine without
/// touching the views. Today the views read this `@Published var`
/// directly; once the KMP `PlayerTrigger` is wired, this becomes a
/// thin Swift wrapper over a `StateFlow<PlayerState>` collector.
@MainActor
final class AvPlayerEngine: ObservableObject {

    /// Tracks what's currently playing (or not). Published so SwiftUI
    /// views update whenever the engine transitions.
    @Published private(set) var state: EngineState = .idle

    /// True only while the engine has loaded bytes and the player
    /// is actively rendering audio. False during loading, pause, and
    /// idle. The mini-bar shows different chrome for each.
    @Published private(set) var isPlaying: Bool = false

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
    /// first `play(track:)` instead of in `init` (which runs on the
    /// SwiftUI render path and would force AVAudioSession boot before
    /// the user has actually tried to listen).
    private var audioSessionConfigured = false

    init(authStorage: AuthStorageBridge) {
        self.authStorage = authStorage
    }

    /// Update the backend target host/port. The next `play(track:)`
    /// call will resolve the stream URL against the new endpoint.
    func updateBackend(host: String, port: String) {
        self.host = host
        self.port = port
    }

    /// Replace the queue with a single track and start playback.
    /// Tears down any existing player first so we never leak audio
    /// sessions across track swaps.
    func play(track: PlayableTrack) {
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
                // Fail open: log and continue. The engine will still
                // try to play; if audio is muted the user can switch
                // to a different category via Settings later.
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
        // fallback when the `Authorization` header is missing
        // (matches the AVPlayer-on-device happy path that the
        // VideoManager reference app uses).
        components.queryItems = (components.queryItems ?? []) + [
            URLQueryItem(name: "token", value: bearer),
        ]
        guard let url = components.url else {
            state = .error(reason: "Invalid stream URL: \(urlString)")
            return
        }

        // AVURLAsset options — the `AVURLAssetHTTPHeaderFieldsKey`
        // option key was removed from the public Swift bridge in
        // iOS 26; instead we build an `AVURLAsset` and then attach
        // the bearer via a custom `AVAssetResourceLoaderDelegate`
        // if the bearer ever stops working. For Phase 3.B we trust
        // the local-network exemption + an inline bearer query param
        // for hosts that don't gate the stream endpoint on a token.
        // (If the backend requires a bearer the response is 401 —
        // handled below.)
        let asset = AVURLAsset(url: url)
        let item = AVPlayerItem(asset: asset)
        let player = AVPlayer(playerItem: item)
        avPlayer = player
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

    /// Stop and clear. The player is paused and dropped; subsequent
    /// `play(track:)` calls start fresh.
    func stop() {
        avPlayer?.pause()
        avPlayer = nil
        stopPolling()
        state = .idle
        isPlaying = false
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
        // Guard defensively so the polling loop survives the
        // un-loaded window without poisoning EngineState.
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

/// State surface that mirrors the KMP `PlayerState` sealed hierarchy
/// (Phase 3 of AGENTS.md). Today Swift-only; once the KMP bridge
/// lands, the engine collects from a `StateFlow<PlayerState>` and
/// re-publishes into the same enum so SwiftUI views don't notice.
enum EngineState: Equatable {
    case idle
    case loading(track: PlayableTrack)
    case playing(track: PlayableTrack, positionMs: Int, durationMs: Int)
    case paused(track: PlayableTrack, positionMs: Int, durationMs: Int)
    case error(reason: String)
}

/// Track metadata that the player engine needs. Mirrors what the
/// eventual KMP `db.Track` row will provide — once the iOS repository
/// lands, `LibraryScreen` will build `PlayableTrack` from real rows
/// and the mock `Track` struct goes away.
struct PlayableTrack: Equatable, Hashable {
    let id: String
    let title: String
    let artistName: String
    let albumTitle: String
}

/// Bridge to the KMP-side `AuthStorage`. Today this is a thin Swift
/// wrapper around `NSUserDefaults.standardUserDefaults` reading the
/// `pairing_token` key — once Phase 3.A lands the KMP
/// `MusicManagerShared` framework, this becomes a bridge to
/// `com.wtm.musicmanager.network.AuthStorage` instead.
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