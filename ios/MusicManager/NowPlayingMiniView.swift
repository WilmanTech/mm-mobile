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
            switch engine.state {
            case .idle, .error:
                EmptyView()
            case .loading(let track),
                 .playing(let track, _, _),
                 .paused(let track, _, _):
                rowContainer(track: track)
            }
        }
        .onAppear { syncVisibility() }
        .onChange(of: engine.state) { _ in syncVisibility() }
        // The full-screen player renders on top of everything,
        // including the TabView. We attach it here so the cover
        // can be triggered from the chrome tap closure below.
        .fullScreenCover(isPresented: $showFullPlayer) {
            NowPlayingView(engine: engine, graph: graph)
        }
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
        let shouldBeVisible: Bool
        switch engine.state {
        case .idle, .error: shouldBeVisible = false
        case .loading, .playing, .paused: shouldBeVisible = true
        }
        if isVisible != shouldBeVisible {
            isVisible = shouldBeVisible
        }
    }
}