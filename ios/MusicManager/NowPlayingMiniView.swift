import SwiftUI

/// Compact now-playing bar shown above the bottom-nav when the player
/// engine has anything loaded. Mirrors the Android
/// `NowPlayingMini` (Compose) surface so the iOS UX matches the
/// Android one once the Kotlin/Native bridge is wired.
///
/// **Phase 3.B** — Swift-only; reads from `AvPlayerEngine` directly.
/// When the KMP `PlayerTrigger` lands, swap `AvPlayerEngine` for a
/// thin Swift wrapper that collects from `StateFlow<PlayerState>`.
struct NowPlayingMiniView: View {

    @ObservedObject var engine: AvPlayerEngine

    var body: some View {
        switch engine.state {
        case .idle, .error:
            EmptyView()
        case .loading(let track):
            loadingRow(track: track)
        case .playing(let track, let positionMs, let durationMs),
             .paused(let track, let positionMs, let durationMs):
            activeRow(track: track, positionMs: positionMs, durationMs: durationMs)
        }
    }

    // MARK: - Sub-views

    private func loadingRow(track: PlayableTrack) -> some View {
        HStack(spacing: 12) {
            artwork
            VStack(alignment: .leading, spacing: 2) {
                Text(track.title)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(Color.mmPrimaryText)
                    .lineLimit(1)
                Text("Loading…")
                    .font(.caption2)
                    .foregroundStyle(Color.mmSecondaryText)
            }
            Spacer()
            ProgressView()
                .tint(Color.mmAccentPrimary)
                .padding(.trailing, 12)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity)
        .background(Color.mmBgCard)
        .overlay(alignment: .top) {
            Rectangle()
                .fill(Color.mmTextDisabled.opacity(0.2))
                .frame(height: 0.5)
        }
    }

    private func activeRow(
        track: PlayableTrack,
        positionMs: Int,
        durationMs: Int,
    ) -> some View {
        HStack(spacing: 12) {
            artwork

            VStack(alignment: .leading, spacing: 4) {
                Text(track.title)
                    .font(.subheadline.weight(.medium))
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
                }
            }

            Spacer()

            Button {
                engine.playPause()
            } label: {
                Image(systemName: engine.isPlaying ? "pause.fill" : "play.fill")
                    .font(.title2)
                    .foregroundStyle(Color.mmAccentPrimary)
                    .frame(width: 36, height: 36)
            }
            .buttonStyle(.plain)
            .padding(.trailing, 4)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity)
        .background(Color.mmBgCard)
        .overlay(alignment: .top) {
            Rectangle()
                .fill(Color.mmTextDisabled.opacity(0.2))
                .frame(height: 0.5)
        }
    }

    private var artwork: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 6)
                .fill(Color.mmBgBase)
            Image(systemName: "music.note")
                .font(.caption)
                .foregroundStyle(Color.mmAccentPrimary)
        }
        .frame(width: 38, height: 38)
    }
}