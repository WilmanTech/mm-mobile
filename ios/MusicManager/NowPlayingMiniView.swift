import SwiftUI
import MusicManagerShared

/// Compact now-playing bar shown above the bottom-nav when the player
/// engine has anything loaded.
///
/// **Phase 3.B+ bug fix (2026-08-14 v3)** — the previous implementation
/// wrapped the chrome in a `NavigationLink` to `NowPlayingView` and
/// overlaid the play/pause button via `.zIndex(1)`. On iOS 26 the
/// `NavigationLink` with `.buttonStyle(.plain)` consistently captured
/// every tap inside its hit region regardless of the overlay's
/// `zIndex`, so the play/pause button never received taps. The user
/// reported "Al tocar el miniplayer en la barra no botones deberia
/// llevar al full player" — taps on the chrome (which should open the
/// full player) did nothing visible because the link swallowed them
/// before the cover transition could render, and the button taps
/// also did nothing because the link still owned the gesture.
///
/// **Fix v3**: replace the `NavigationLink` chrome with a plain
/// `Button { showFullPlayer = true } label: { chromeRow(...) }` and
/// present `NowPlayingView` via `.fullScreenCover(isPresented:)`.
/// This removes `NavigationLink` from the gesture path entirely —
/// the button closure fires only when the chrome is tapped, and the
/// fullScreenCover renders `NowPlayingView` on top of the entire
/// view hierarchy (above the TabView, above the mini player).
///
/// **Why `.fullScreenCover` and not `.sheet` or NavigationStack push:**
/// `.sheet` doesn't go above the TabView on iOS 26 — the user sees a
/// half-card slide-up that still shows the tab bar. `.fullScreenCover`
/// takes the whole screen, which is what Apple Music / Spotify do for
/// the player. NavigationStack push would work but the parent
/// `MainTabView` doesn't own a `NavigationStack` (each tab does), so
/// the modal pattern keeps things self-contained.
struct NowPlayingMiniView: View {

    @ObservedObject var engine: AvPlayerEngine
    let graph: LibraryEntry.Graph

    /// Controls the slide-in / slide-out animation when the engine
    /// transitions between `.idle`/`.error` and the playing states.
    @State private var isVisible: Bool = false

    /// Controls the full-screen player presentation. Set to `true`
    /// when the user taps the chrome (artwork / title / progress /
    /// any non-button area); `false` to dismiss.
    @State private var showFullPlayer: Bool = false

    var body: some View {
        Group {
            // Phase 3.C: previously the mini only appeared when the
            // engine had an active `state` (loading/playing/paused).
            // After a cold launch with a restored queue, the engine
            // is `.idle` until the user opts in (see `init` for the
            // rationale), which meant the restored queue was
            // invisible. We add a dedicated "restored queue" arm
            // that shows a "Resume queue" chrome with the same
            // tap-to-open-full-player behaviour so the user has a
            // way back into the previously-playing context.
            if engine.queue.isEmpty {
                EmptyView()
            } else if Self.isInactive(engine.state) {
                restoredQueueRow
            } else {
                activeRow
            }
        }
        .onAppear { syncVisibility() }
        .onChange(of: engine.state) { _ in syncVisibility() }
        .onChange(of: engine.queue) { _ in syncVisibility() }
        // The full-screen player renders on top of everything,
        // including the TabView. We attach it here so the cover
        // can be triggered from the chrome tap closure below.
        .fullScreenCover(isPresented: $showFullPlayer) {
            // Phase 3.C: `NowPlayingView.init` expects
            // `Binding<Bool?>?` (the Optional lets the view
            // distinguish between "I was presented via
            // NavigationLink" and "I was presented via
            // fullScreenCover"). The cover's binding is
            // `Binding<Bool>`, so we bridge by mapping to/from
            // `Bool?` and writing back to `$showFullPlayer` on
            // dismiss. This is what makes the chevron-down /
            // drag-down gestures work — the parent cover
            // observes the binding flip and tears itself down.
            NowPlayingView(
                engine: engine,
                graph: graph,
                isPresented: Binding<Bool?>(
                    get: { self.showFullPlayer ? true : nil },
                    set: { newValue in
                        if newValue != true { self.showFullPlayer = false }
                    }
                )
            )
        }
    }

    /// True when the engine has no track actively loaded — i.e. either
    /// `.idle` (fresh launch or after `stop()`) or `.error`. Phase
    /// 3.C treats both as "queue restored from disk, waiting for the
    /// user to opt in".
    private static func isInactive(_ state: EngineState) -> Bool {
        switch state {
        case .idle, .error: return true
        case .loading, .playing, .paused: return false
        }
    }

    /// The active-state chrome (loading / playing / paused). The
    /// current track comes from `engine.state` directly, which
    /// carries the polling-tick position.
    @ViewBuilder
    private var activeRow: some View {
        switch engine.state {
        case .idle, .error:
            // Unreachable: caller already gated on
            // `!isInactive(state)`.
            EmptyView()
        case .loading(let track),
             .playing(let track, _, _),
             .paused(let track, _, _):
            rowContainer(track: track)
        }
    }

    /// Restored-queue chrome shown after a cold launch when
    /// `engine.queue` has items but the engine is still `.idle` (or
    /// `.error`). Tapping the row opens the full player where the
    /// user can resume from `currentIndex` via the play button.
    @ViewBuilder
    private var restoredQueueRow: some View {
        let track: PlayableTrack = {
            // Prefer the track at `currentIndex`; fall back to the
            // first track in the queue when `currentIndex` is out
            // of bounds (defensive — `AvPlayerEngine.init` keeps
            // them consistent, but the queue snapshot may have
            // been written by an older version that allowed drift).
            if engine.queue.indices.contains(engine.currentIndex) {
                return engine.queue[engine.currentIndex]
            }
            return engine.queue.first ?? PlayableTrack(
                id: "", title: "Queue", artistName: "", albumTitle: "", albumId: ""
            )
        }()

        ZStack(alignment: .trailing) {
            Button {
                showFullPlayer = true
            } label: {
                restoredChromeRow(track: track)
            }
            .buttonStyle(.plain)
            .contentShape(Rectangle())

            // Phase 3.C: a play button so the user can resume
            // directly from the mini without opening the full
            // player. Reuses the queue's `currentIndex` (the same
            // one that was current when the app was last alive).
            Button {
                if engine.queue.indices.contains(engine.currentIndex) {
                    engine.play(track: engine.queue[engine.currentIndex], in: engine.queue)
                } else if let first = engine.queue.first {
                    engine.play(track: first, in: engine.queue)
                }
            } label: {
                Image(systemName: "play.fill")
                    .font(.title2.weight(.semibold))
                    .foregroundStyle(Color.mmAccentPrimary)
                    .frame(width: 48, height: 48)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Resume queue")
            .padding(.trailing, 4)
        }
        .frame(height: isVisible ? 64 : 0)
        .opacity(isVisible ? 1 : 0)
        .offset(y: isVisible ? 0 : 80)
        .clipped()
        .animation(.easeInOut(duration: 0.25), value: isVisible)
    }

    /// Visual chrome for the restored-queue state. Shows the
    /// would-be-current track title + a "Resume" subtitle so the
    /// user can tell this is their previous session, not a fresh
    /// pick.
    private func restoredChromeRow(track: PlayableTrack) -> some View {
        HStack(spacing: 12) {
            artwork(track: track)
            VStack(alignment: .leading, spacing: 4) {
                Text(track.title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.mmPrimaryText)
                    .lineLimit(1)
                Text("Resume queue · \(track.artistName)")
                    .font(.caption2)
                    .foregroundStyle(Color.mmSecondaryText)
                    .lineLimit(1)
            }
            Spacer().frame(width: 56)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity)
        .background(
            ZStack {
                Color.mmBgCard
                Rectangle()
                    .fill(Color.mmTextDisabled.opacity(0.4))
                    .frame(height: 0.5)
                    .frame(maxHeight: .infinity, alignment: .top)
            }
            .shadow(color: .black.opacity(0.3), radius: 8, y: -2)
        )
        .contentShape(Rectangle())
    }

    /// The bar is a `ZStack` of two layers:
    ///   1. A `Button` that fills the whole row and toggles
    ///      `showFullPlayer`. The button's closure is the only path
    ///      that triggers the full player, so taps on the chrome
    ///      unambiguously open it.
    ///   2. A `playPauseButton` overlay on the trailing edge, with
    ///      `.allowsHitTesting(true)` + 48x48 frame so it captures
    ///      taps meant for the play/pause action and prevents the
    ///      button underneath from firing.
    ///
    /// The play/pause button is layered ON TOP of the chrome
    /// button in the ZStack. SwiftUI's hit-testing walks the ZStack
    /// from trailing to leading, so the play/pause button gets
    /// first crack at any tap in its 48x48 rectangle; anything
    /// outside that rectangle falls through to the chrome button.
    @ViewBuilder
    private func rowContainer(track: PlayableTrack) -> some View {
        ZStack(alignment: .trailing) {
            // Layer 1: full-width chrome button. Opens the full
            // player. `.contentShape(Rectangle())` ensures the tap
            // region is exactly the chrome rectangle, not the
            // surrounding ZStack frame.
            Button {
                showFullPlayer = true
            } label: {
                chromeRow(track: track)
            }
            .buttonStyle(.plain)
            .contentShape(Rectangle())

            // Layer 2: play/pause button overlay. `.allowsHitTesting`
            // is implicit (default true) but stated explicitly so the
            // intent is obvious. The 48x48 frame + `.contentShape`
            // caps the hit area so taps outside the button reach the
            // chrome button underneath.
            playPauseButton
                .frame(width: 48, height: 48)
                .contentShape(Rectangle())
                .padding(.trailing, 4)
                .allowsHitTesting(true)
        }
        .frame(height: isVisible ? 64 : 0)
        .opacity(isVisible ? 1 : 0)
        .offset(y: isVisible ? 0 : 80)
        .clipped()
        .animation(.easeInOut(duration: 0.25), value: isVisible)
    }

    /// The visual chrome inside the full-width button. Tap anywhere
    /// on this rectangle opens the full `NowPlayingView`.
    private func chromeRow(track: PlayableTrack) -> some View {
        let positionMs: Int
        let durationMs: Int
        switch engine.state {
        case .loading: positionMs = 0; durationMs = 0
        case .playing(_, let p, let d), .paused(_, let p, let d): positionMs = p; durationMs = d
        case .idle, .error: positionMs = 0; durationMs = 0
        }

        return HStack(spacing: 12) {
            artwork(track: track)

            VStack(alignment: .leading, spacing: 4) {
                Text(track.title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.mmPrimaryText)
                    .lineLimit(1)
                Text("\(track.artistName) — \(track.albumTitle)")
                    .font(.caption2)
                    .foregroundStyle(Color.mmSecondaryText)
                    .lineLimit(1)

                if durationMs > 0 {
                    ProgressView(
                        value: Double(positionMs),
                        total: Double(durationMs)
                    )
                    .tint(Color.mmAccentPrimary)
                } else if case .loading = engine.state {
                    ProgressView()
                        .tint(Color.mmAccentPrimary)
                }
            }

            // Right-side padding reserved for the 48pt-wide play/pause
            // button overlay so the title doesn't run underneath it.
            Spacer().frame(width: 56)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity)
        .background(
            // Solid background + top divider + upward shadow so the
            // mini player reads as a distinct surface, not part of
            // the scrolling content underneath. The user reported
            // "se empasta con los elementos del scroll" — the fix
            // is a 1pt top divider + slightly elevated background
            // opacity so the bar feels like a tray, not part of
            // the content.
            ZStack {
                Color.mmBgCard
                Rectangle()
                    .fill(Color.mmTextDisabled.opacity(0.4))
                    .frame(height: 0.5)
                    .frame(maxHeight: .infinity, alignment: .top)
            }
            .shadow(color: .black.opacity(0.3), radius: 8, y: -2)
        )
        .contentShape(Rectangle())
    }

    /// Play/pause button — sibling of the chrome button with
    /// `.allowsHitTesting(true)`. Its 48x48 frame + `.contentShape`
    /// restricts the hit area to the button rectangle so taps
    /// outside the button fall through to the chrome button.
    private var playPauseButton: some View {
        Button {
            engine.playPause()
        } label: {
            Image(systemName: engine.isPlaying ? "pause.fill" : "play.fill")
                .font(.title2.weight(.semibold))
                .foregroundStyle(Color.mmAccentPrimary)
                .frame(width: 48, height: 48)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(engine.isPlaying ? "Pause" : "Play")
        .accessibilityIdentifier("NowPlayingMini.playPause")
    }

    private func artwork(track: PlayableTrack) -> some View {
        ZStack {
            RoundedRectangle(cornerRadius: 6)
                .fill(Color.mmBgBase)
            Image(systemName: "music.note")
                .font(.caption)
                .foregroundStyle(Color.mmAccentPrimary)
        }
        .frame(width: 38, height: 38)
    }

    private func syncVisibility() {
        // Phase 3.C: visibility now considers `engine.queue` too.
        // Before this change the mini only showed up when the
        // engine had an active `state`, so a restored queue
        // (queue non-empty, state = .idle) was invisible. Now we
        // show the mini whenever either an active state is loaded
        // OR a restored queue is sitting on disk.
        let shouldBeVisible: Bool = !engine.queue.isEmpty
        if isVisible != shouldBeVisible {
            isVisible = shouldBeVisible
        }
    }
}