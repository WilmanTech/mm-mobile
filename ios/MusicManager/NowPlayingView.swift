import SwiftUI

/// Full-screen "now playing" page reached by tapping the
/// `NowPlayingMiniView` chrome. Mirrors the Apple Music / Spotify
/// full-screen player: large artwork, scrubber, transport controls,
/// shuffle toggle, and a queue sheet trigger.
///
/// **Phase 3.B+** — the engine now has a queue + shuffle state, so
/// this page exposes them via the chrome. The page reads from
/// `AvPlayerEngine` directly via `@ObservedObject`; once the KMP
/// `PlayerTrigger` lands it swaps for a Swift wrapper around the
/// `StateFlow<PlayerState>` collector.
///
/// **Layout** — uses an iOS 26 `.glassEffect(...)` for the surface so
/// the brand palette + glass material stays consistent with the
/// rest of the app. On older OS we fall back to a flat colour card.
struct NowPlayingView: View {

    @ObservedObject var engine: AvPlayerEngine
    @Environment(\.dismiss) private var dismiss

    /// Local copy of the seek position so the scrubber feels smooth
    /// while the user drags. The engine's position polls every 250ms
    /// which is too coarse for a thumb drag — we update the
    /// `@Published` once when the drag ends.
    @State private var dragPositionMs: Double? = nil

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
            }
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
        VStack(spacing: 28) {
            artwork
                .frame(width: 280, height: 280)
                .padding(.top, 16)

            VStack(spacing: 6) {
                Text(track.title)
                    .font(.title2.weight(.semibold))
                    .foregroundStyle(Color.mmPrimaryText)
                    .lineLimit(1)
                Text("\(track.artistName) — \(track.albumTitle)")
                    .font(.subheadline)
                    .foregroundStyle(Color.mmSecondaryText)
                    .lineLimit(1)
            }
            .padding(.horizontal, 24)

            scrubber(positionMs: positionMs, durationMs: durationMs)
                .padding(.horizontal, 24)

            transportControls
                .padding(.horizontal, 24)

            secondaryControls
                .padding(.horizontal, 32)
        }
        .padding(.bottom, 24)
    }

    // MARK: - Sub-views

    private var artwork: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 16)
                .fill(Color.mmBgCard)
            Image(systemName: "music.note")
                .font(.system(size: 80))
                .foregroundStyle(Color.mmAccentPrimary)
        }
        .shadow(color: .black.opacity(0.4), radius: 24, y: 8)
    }

    private func scrubber(positionMs: Int, durationMs: Int) -> some View {
        // Use the drag position if the user is actively scrubbing,
        // otherwise show the engine's polled position.
        let displayedPosition = dragPositionMs ?? Double(positionMs)
        let totalMs = Double(durationMs > 0 ? durationMs : 1)

        return VStack(spacing: 6) {
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
    }

    private var transportControls: some View {
        HStack(spacing: 32) {
            // Previous
            Button(action: engine.previous) {
                Image(systemName: "backward.fill")
                    .font(.title)
                    .foregroundStyle(Color.mmPrimaryText)
            }
            .disabled(engine.queue.isEmpty)

            // Play / Pause — big button
            Button(action: engine.playPause) {
                Image(systemName: engine.isPlaying ? "pause.fill" : "play.fill")
                    .font(.system(size: 44))
                    .foregroundStyle(Color.mmBgBase)
                    .frame(width: 76, height: 76)
                    .background(Color.mmAccentPrimary)
                    .clipShape(Circle())
            }

            // Next
            Button(action: engine.next) {
                Image(systemName: "forward.fill")
                    .font(.title)
                    .foregroundStyle(Color.mmPrimaryText)
            }
            .disabled(engine.queue.isEmpty)
        }
    }

    private var secondaryControls: some View {
        HStack {
            // Shuffle
            Button(action: engine.toggleShuffle) {
                Image(systemName: "shuffle")
                    .font(.title3)
                    .foregroundStyle(
                        engine.isShuffled
                            ? Color.mmAccentPrimary
                            : Color.mmSecondaryText
                    )
            }

            Spacer()

            // Queue indicator (count of remaining tracks)
            if engine.queue.count > 1 {
                let remaining = engine.queue.count - engine.currentIndex - 1
                if remaining > 0 {
                    Text("\(remaining + 1) tracks")
                        .font(.footnote)
                        .foregroundStyle(Color.mmSecondaryText)
                }
            }

            Spacer()

            // Queue sheet trigger
            NavigationLink {
                QueueView(engine: engine)
            } label: {
                Image(systemName: "list.bullet")
                    .font(.title3)
                    .foregroundStyle(Color.mmSecondaryText)
            }
            .disabled(engine.queue.isEmpty)
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
                }
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
