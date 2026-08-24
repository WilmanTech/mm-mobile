import SwiftUI
import MusicManagerShared

/// Phase 3.B+ parity with Android's `PlaylistDetailScreen`. Reached
/// from `SearchView`'s playlist sub-tab.
///
/// Header shows the playlist name + description + total track count.
/// Below is the list of tracks in playlist order. Tap to play with
/// the playlist as the queue.
struct PlaylistDetailView: View {

    let graph: LibraryEntry.Graph
    let playlistId: Int64
    @ObservedObject var player: AvPlayerEngine

    @State private var playlist: MusicManagerShared.Playlist?
    @State private var tracks: [SwiftTrack] = []
    @State private var isLoading: Bool = true

    private var baseURL: URL {
        URL(string: "http://\(graph.baseHost):\(graph.basePort)") ?? URL(string: "about:blank")!
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                header
                trackList
            }
        }
        .background(Color.mmBackground.ignoresSafeArea())
        .navigationTitle(playlist?.name ?? "Lista")
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
    }

    // MARK: - Header

    private var header: some View {
        VStack(spacing: 12) {
            // Playlists don't have a cover on the backend (no `cover_path`
            // in PlaylistDto). We use a large `music.note.list` placeholder
            // with the playlist's first letter overlaid.
            ZStack {
                RoundedRectangle(cornerRadius: 12)
                    .fill(Color.mmBgCard)
                Image(systemName: "music.note.list")
                    .font(.system(size: 56))
                    .foregroundStyle(Color.mmAccentPrimary)
                Text(playlist?.name.prefix(1).uppercased() ?? "?")
                    .font(.system(size: 48, weight: .bold))
                    .foregroundStyle(Color.mmPrimaryText)
                    .padding(.top, 48)
            }
            .frame(width: 220, height: 220)
            .shadow(color: .black.opacity(0.4), radius: 16, y: 4)
            .padding(.top, 16)

            if let playlist {
                VStack(spacing: 4) {
                    Text(playlist.name)
                        .font(.title2.weight(.bold))
                        .foregroundStyle(Color.mmPrimaryText)
                        .multilineTextAlignment(.center)
                        .lineLimit(2)

                    if let desc = playlist.description_, !desc.isEmpty {
                        Text(desc)
                            .font(.subheadline)
                            .foregroundStyle(Color.mmSecondaryText)
                            .multilineTextAlignment(.center)
                            .lineLimit(3)
                    }

                    Text("\(tracks.count) \(tracks.count == 1 ? "pista" : "pistas")")
                        .font(.caption)
                        .foregroundStyle(Color.mmTextDisabled)
                }
                .padding(.horizontal, 24)

                HStack(spacing: 12) {
                    Button(action: shuffleAndPlay) {
                        Label("Aleatoria", systemImage: "shuffle")
                            .font(.subheadline.weight(.semibold))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 10)
                            .background(Color.mmBgCard)
                            .foregroundStyle(Color.mmPrimaryText)
                            .clipShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .disabled(tracks.isEmpty)

                    Button(action: playInOrder) {
                        Label("Reproducir", systemImage: "play.fill")
                            .font(.subheadline.weight(.semibold))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 10)
                            .background(Color.mmAccentPrimary)
                            .foregroundStyle(Color.mmBgBase)
                            .clipShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .disabled(tracks.isEmpty)
                }
                .padding(.horizontal, 24)
                .padding(.top, 4)
            }
        }
        .padding(.bottom, 16)
    }

    // MARK: - Track list

    private var trackList: some View {
        VStack(alignment: .leading, spacing: 0) {
            if isLoading {
                ProgressView()
                    .tint(Color.mmAccentPrimary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 24)
            } else if tracks.isEmpty {
                Text("Esta lista está vacía")
                    .font(.subheadline)
                    .foregroundStyle(Color.mmSecondaryText)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 24)
                    .background(Color.mmBgCard)
                    .clipShape(RoundedRectangle(cornerRadius: MusicManagerTheme.cornerRadius))
                    .padding(.horizontal, 16)
            } else {
                VStack(spacing: 0) {
                    ForEach(Array(tracks.enumerated()), id: \.element.id) { idx, track in
                        detailTrackRow(track: track, index: idx + 1)
                        if idx < tracks.count - 1 {
                            Divider()
                                .background(Color.mmTextDisabled.opacity(0.2))
                                .padding(.leading, 64)
                        }
                    }
                }
                .background(Color.mmBgCard)
                .clipShape(RoundedRectangle(cornerRadius: MusicManagerTheme.cornerRadius))
                .padding(.horizontal, 16)
            }
        }
        .padding(.bottom, 24)
    }

    private func detailTrackRow(track: SwiftTrack, index: Int) -> some View {
        Button {
            let queue = tracks.map(trackToPlayable)
            let target = trackToPlayable(track)
            player.play(track: target, in: queue)
        } label: {
            HStack(spacing: 12) {
                Text("\(index)")
                    .font(.subheadline.monospacedDigit())
                    .foregroundStyle(Color.mmSecondaryText)
                    .frame(width: 28, alignment: .trailing)

                CoverArtImage(
                    coverPath: nil,
                    baseURL: baseURL,
                    placeholderSeed: playlist?.name ?? "playlist",
                    placeholder: {
                        CoverArtPlaceholder(
                            seed: playlist?.name ?? "playlist",
                            systemImage: "music.note.list",
                            cornerRadius: 4,
                            initial: playlist?.name.first.map(String.init),
                        )
                    },
                )
                .frame(width: 40, height: 40)

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

                Text(formatDuration(track.durationMs))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(Color.mmSecondaryText)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    // MARK: - Data loading

    private func load() async {
        isLoading = true
        defer { isLoading = false }

        do {
            let p = try await graph.libraryRepository.playlistById(id: playlistId)
            await MainActor.run { self.playlist = p }
        } catch {
            // Fall through with nil playlist.
        }

        let flow = graph.libraryRepository.observePlaylistTracks(playlistId: playlistId)
        flow.collect(
            collector: PlaylistTracksCollector { mapped in
                Task { @MainActor in
                    // Backend already orders by position, but sort
                    // defensively in case the SQL query changes.
                    self.tracks = mapped
                }
            },
        ) { _ in }
    }

    private func shuffleAndPlay() {
        let queue = tracks.map(trackToPlayable).shuffled()
        guard let first = queue.first else { return }
        player.isShuffled = true
        player.play(track: first, in: queue)
    }

    private func playInOrder() {
        let queue = tracks.map(trackToPlayable)
        guard let first = queue.first else { return }
        player.play(track: first, in: queue)
    }

    private func trackToPlayable(_ track: SwiftTrack) -> PlayableTrack {
        PlayableTrack(
            id: String(track.id),
            title: track.title,
            artistName: track.artistName,
            albumTitle: track.albumTitle,
            albumId: String(track.albumId),
        )
    }

    private func formatDuration(_ ms: Int64) -> String {
        let totalSeconds = Int(ms / 1000)
        let minutes = totalSeconds / 60
        let seconds = totalSeconds % 60
        return String(format: "%d:%02d", minutes, seconds)
    }
}

private final class PlaylistTracksCollector: NSObject, Kotlinx_coroutines_coreFlowCollector {
    private let onEmit: ([SwiftTrack]) -> Void
    init(onEmit: @escaping ([SwiftTrack]) -> Void) { self.onEmit = onEmit }

    func emit(value: Any?, completionHandler: @escaping (Error?) -> Void) {
        if let list = value as? [Any] {
            let mapped = list.compactMap { $0 as? MusicManagerShared.Track }
                .map(SwiftTrack.init)
            onEmit(mapped)
        } else {
            onEmit([])
        }
        completionHandler(nil)
    }
}