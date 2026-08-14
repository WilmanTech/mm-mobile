import SwiftUI

/// Full-screen "now playing" page reached by tapping the
/// `NowPlayingMiniView` chrome. Mirrors the Apple Music / Spotify
/// full-screen player: large artwork, scrubber, transport controls,
/// shuffle + repeat toggles, queue sheet, album link.
///
/// **Phase 3.B+** — the engine now has a queue + shuffle state, so
/// this page exposes them via the chrome. The page reads from
/// `AvPlayerEngine` directly via `@ObservedObject`; once the KMP
/// `PlayerTrigger` lands it swaps for a Swift wrapper around the
/// `StateFlow<PlayerState>` collector.
///
/// **Phase 3.B++** — added repeat mode (off / all / one), the album
/// link (tap album title to navigate back), and iOS 26 glassEffect
/// on the artwork card for the brand material consistency.
///
/// **Why `contentShape(Rectangle())` on the play/pause button** —
/// earlier versions of this view had the play/pause button inside an
/// HStack next to a Slider whose track extended across the full
/// horizontal width when the track duration was unknown (Slider's
/// `0...1` range). SwiftUI's hit-test resolution was passing touches
/// to the Slider instead of the Button. Wrapping the play button's
/// label in `contentShape(Rectangle())` + `.buttonStyle(.borderless)`
/// forces a precise rectangular hit region that doesn't overlap
/// the slider's track. Same fix on prev/next so the whole transport
/// row behaves reliably on small screens.
struct NowPlayingView: View {

    @ObservedObject var engine: AvPlayerEngine
    @Environment(\.dismiss) private var dismiss

    /// Local copy of the seek position so the scrubber feels smooth
    /// while the user drags. The engine's position polls every 250ms
    /// which is too coarse for a thumb drag — we update the
    /// `@Published` once when the drag ends.
    @State private var dragPositionMs: Double? = nil

    /// Phase 3.B++: navigation to the source album. We don't have a
    /// full AlbumDetail screen yet, so we just dismiss back to the
    /// library for now — the gesture still feels right because the
    /// album title is rendered as a button.
    @State private var showingAlbumInfo: Bool = false

    var body: some View {
        ZStack {
            Color.mmBackground
                .ignoresSafeArea()

            switch engine.state {
            case .idle, .error:
                emptyState
            case .loading(let track):
                loadingState(track: track)
            case .playing(let track, let positionMs, let durationMs),
                 .paused(let track, let positionMs, let durationMs):
                activeState(
                    track: track,
                    positionMs: positionMs,
                    durationMs: durationMs,
                )
            }
        }
        .navigationTitle("Now Playing")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button {
                    dismiss()
                } label: {
                    Image(systemName: "chevron.down")
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(Color.mmPrimaryText)
                }
                .accessibilityLabel("Close now playing")
            }
        }
        .alert("Coming soon", isPresented: $showingAlbumInfo) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("Album detail screens land in Phase 3.C (separate PR).")
        }
    }

    // MARK: - States

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "music.note.list")
                .font(.system(size: 48))
                .foregroundStyle(Color.mmAccentPrimary)
            Text("Nothing playing yet")
                .font(.title3.weight(.semibold))
                .foregroundStyle(Color.mmPrimaryText)
            Text("Pick a track from your library and tap it.")
                .font(.subheadline)
                .foregroundStyle(Color.mmSecondaryText)
        }
        .padding(32)
    }

    private func loadingState(track: PlayableTrack) -> some View {
        VStack(spacing: 24) {
            artwork
                .frame(width: 240, height: 240)
            VStack(spacing: 4) {
                Text(track.title)
                    .font(.title2.weight(.semibold))
                    .foregroundStyle(Color.mmPrimaryText)
                Text("\(track.artistName) — \(track.albumTitle)")
                    .font(.subheadline)
                    .foregroundStyle(Color.mmSecondaryText)
            }
            ProgressView()
                .tint(Color.mmAccentPrimary)
        }
        .padding(32)
    }

    private func activeState(
        track: PlayableTrack,
        positionMs: Int,
        durationMs: Int,
    ) -> some View {
        ScrollView {
            VStack(spacing: 28) {
                artwork
                    .frame(width: 280, height: 280)
                    .padding(.top, 16)

                trackCaption(track: track)

                scrubber(positionMs: positionMs, durationMs: durationMs)

                transportControls

                secondaryControls
            }
            .padding(.bottom, 24)
        }
    }

    // MARK: - Sub-views

    @ViewBuilder
    private var artwork: some View {
        // Phase 3.B++: iOS 26 glassEffect on the artwork card. The
        // glass material layers the brand yellow accent over a
        // translucent background — matches the brand identity work
        // in Phase 4.A.2. Falls back to a flat card on older OS.
        let base = ZStack {
            RoundedRectangle(cornerRadius: 16)
                .fill(Color.mmBgCard)
            Image(systemName: "music.note")
                .font(.system(size: 80))
                .foregroundStyle(Color.mmAccentPrimary)
        }
        .shadow(color: .black.opacity(0.4), radius: 24, y: 8)

        if #available(iOS 26.0, *) {
            base.glassEffect(
                MusicManagerTheme.glass,
                in: RoundedRectangle(cornerRadius: 16)
            )
        } else {
            base
        }
    }

    private func trackCaption(track: PlayableTrack) -> some View {
        VStack(spacing: 6) {
            Text(track.title)
                .font(.title2.weight(.semibold))
                .foregroundStyle(Color.mmPrimaryText)
                .lineLimit(1)
            Button {
                showingAlbumInfo = true
            } label: {
                Text("\(track.artistName) — \(track.albumTitle)")
                    .font(.subheadline)
                    .foregroundStyle(Color.mmSecondaryText)
                    .lineLimit(1)
                    .underline(/* show the link affordance */ true, color: Color.mmSecondaryText.opacity(0.3))
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Album \(track.albumTitle)")
        }
        .padding(.horizontal, 24)
    }

    @ViewBuilder
    private func scrubber(positionMs: Int, durationMs: Int) -> some View {
        // Only render the Slider when we have a real duration. With
        // `0...1` the Slider's track extends across the full width
        // and intercepts touches meant for the transport buttons
        // below. Showing a thin progress bar (no gesture) until the
        // AVPlayer reports durationMs > 0 keeps the hit-testing
        // unambiguous.
        let totalMs = Double(durationMs > 0 ? durationMs : 1)
        let displayedPosition = dragPositionMs ?? Double(positionMs)

        if durationMs > 0 {
            VStack(spacing: 6) {
                Slider(
                    value: Binding(
                        get: { displayedPosition },
                        set: { dragPositionMs = $0 },
                    ),
                    in: 0...totalMs,
                    onEditingChanged: { editing in
                        if !editing, let finalPosition = dragPositionMs {
                            engine.seek(positionMs: Int(finalPosition))
                            dragPositionMs = nil
                        }
                    },
                )
                .tint(Color.mmAccentPrimary)

                HStack {
                    Text(formatMs(Int(displayedPosition)))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(Color.mmSecondaryText)
                    Spacer()
                    Text(formatMs(durationMs))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(Color.mmSecondaryText)
                }
            }
            .padding(.horizontal, 24)
        } else {
            // Indeterminate progress: thin bar, no gestures.
            VStack(spacing: 6) {
                ProgressView()
                    .tint(Color.mmAccentPrimary)
                    .frame(maxWidth: .infinity)
                Text("Loading…")
                    .font(.caption2)
                    .foregroundStyle(Color.mmSecondaryText)
            }
            .padding(.horizontal, 24)
        }
    }

    /// Big previous / play-pause / next row. Each Button uses
    /// `.contentShape(Rectangle())` so the hit area is exactly the
    /// label rectangle, never extending into the scrubber above or
    /// the secondary controls below.
    private var transportControls: some View {
        HStack(spacing: 36) {
            transportButton(systemImage: "backward.fill", action: engine.previous)
                .disabled(engine.queue.isEmpty)

            playPauseButton

            transportButton(systemImage: "forward.fill", action: engine.next)
                .disabled(engine.queue.isEmpty)
        }
        .padding(.horizontal, 24)
    }

    private func transportButton(
        systemImage: String,
        action: @escaping () -> Void,
    ) -> some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 28, weight: .semibold))
                .foregroundStyle(Color.mmPrimaryText)
                .frame(width: 56, height: 56)
                .contentShape(Rectangle())
        }
        .buttonStyle(.borderless)
    }

    private var playPauseButton: some View {
        Button(action: engine.playPause) {
            ZStack {
                Circle()
                    .fill(Color.mmAccentPrimary)
                Image(systemName: engine.isPlaying ? "pause.fill" : "play.fill")
                    .font(.system(size: 32, weight: .semibold))
                    .foregroundStyle(Color.mmBgBase)
            }
            .frame(width: 84, height: 84)
            .contentShape(Rectangle())
        }
        .buttonStyle(.borderless)
        .accessibilityLabel(engine.isPlaying ? "Pause" : "Play")
    }

    /// Phase 3.B++: shuffle + repeat toggles + queue indicator +
    /// queue sheet link. Repeat cycles off → all → one → off
    /// (Apple Music convention).
    private var secondaryControls: some View {
        HStack {
            Button(action: engine.toggleShuffle) {
                Image(systemName: "shuffle")
                    .font(.title3)
                    .foregroundStyle(
                        engine.isShuffled
                            ? Color.mmAccentPrimary
                            : Color.mmSecondaryText
                    )
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(engine.isShuffled ? "Shuffle on" : "Shuffle off")

            Spacer()

            if engine.queue.count > 1 {
                let remaining = engine.queue.count - engine.currentIndex - 1
                if remaining > 0 {
                    Text("\(remaining + 1) tracks")
                        .font(.footnote)
                        .foregroundStyle(Color.mmSecondaryText)
                }
            }

            Spacer()

            Button(action: engine.toggleRepeat) {
                Image(systemName: engine.repeatMode.systemImageName)
                    .font(.title3)
                    .foregroundStyle(repeatTintColor)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .accessibilityLabel("Repeat \(engine.repeatMode.label)")

            Spacer().frame(width: 12)

            NavigationLink {
                QueueView(engine: engine)
            } label: {
                Image(systemName: "list.bullet")
                    .font(.title3)
                    .foregroundStyle(Color.mmSecondaryText)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .disabled(engine.queue.isEmpty)
            .accessibilityLabel("Queue")
        }
        .padding(.horizontal, 20)
    }

    /// Repeat button tint: same yellow as shuffle when active, but
    /// `repeat.one` uses a slightly different shade so the user can
    /// tell the two modes apart at a glance.
    private var repeatTintColor: Color {
        switch engine.repeatMode {
        case .off:
            return Color.mmSecondaryText
        case .all:
            return Color.mmAccentPrimary
        case .one:
            return Color.mmAccentHover
        }
    }

    private func formatMs(_ ms: Int) -> String {
        let totalSeconds = max(0, Int(ms / 1000))
        let minutes = totalSeconds / 60
        let seconds = totalSeconds % 60
        return String(format: "%d:%02d", minutes, seconds)
    }
}

/// Queue sheet reached from `NowPlayingView`. Lists the current
/// queue with the active track highlighted; tapping any row jumps
/// to that track via `engine.jumpTo(index:)`.
struct QueueView: View {

    @ObservedObject var engine: AvPlayerEngine

    var body: some View {
        List {
            ForEach(Array(engine.queue.enumerated()), id: \.element.id) { idx, track in
                Button {
                    engine.jumpTo(index: idx)
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: idx == engine.currentIndex
                              ? "speaker.wave.2.fill"
                              : "music.note")
                            .foregroundStyle(
                                idx == engine.currentIndex
                                    ? Color.mmAccentPrimary
                                    : Color.mmSecondaryText
                            )
                            .frame(width: 24)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(track.title)
                                .font(.subheadline.weight(.medium))
                                .foregroundStyle(Color.mmPrimaryText)
                                .lineLimit(1)
                            Text("\(track.artistName) — \(track.albumTitle)")
                                .font(.caption)
                                .foregroundStyle(Color.mmSecondaryText)
                                .lineLimit(1)
                        }
                        Spacer()
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.borderless)
                .listRowBackground(Color.mmBgCard)
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(Color.mmBackground)
        .navigationTitle("Queue")
        .navigationBarTitleDisplayMode(.inline)
    }
}
