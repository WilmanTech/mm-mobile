import SwiftUI
import MusicManagerShared

/// Phase 3.B+ parity with Android's `AlbumDetailScreen`. Reached
/// from `LibraryScreen` artist chips, `SearchView` album rows, and
/// `NowPlayingView`'s album link (Phase 3.C+ polish).
///
/// Header shows large cover art, album title, artist (tap navigates
/// to `ArtistDetailView`), year + genre metadata, and Shuffle / Play
/// buttons that seed the queue with the album's tracks.
struct AlbumDetailView: View {

    let graph: LibraryEntry.Graph
    let albumId: Int64
    @ObservedObject var player: AvPlayerEngine

    @State private var album: MusicManagerShared.Album?
    @State private var tracks: [SwiftTrack] = []
    @State private var isLoading: Bool = true

    private var baseURL: URL {
        URL(string: "http://\(graph.baseHost):\(graph.basePort)") ?? URL(string: "about:blank")!
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                header
                trackList
            }
        }
        .background(Color.mmBackground.ignoresSafeArea())
        .navigationTitle(album?.title ?? "Álbum")
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
    }

    // MARK: - Header

    private var header: some View {
        VStack(spacing: 16) {
            CoverArtImage(
                coverPath: album?.cover_path,
                baseURL: baseURL,
                contentMode: .fill,
                placeholderSeed: album?.title,
                placeholder: {
                    CoverArtPlaceholder(
                        seed: album?.title,
                        systemImage: "rectangle.stack",
                        cornerRadius: 12,
                        initial: album?.title.first.map(String.init),
                    )
                },
                failure: {
                    CoverArtPlaceholder(
                        seed: album?.title,
                        systemImage: "rectangle.stack",
                        cornerRadius: 12,
                        initial: album?.title.first.map(String.init),
                    )
                },
            )
            .frame(width: 220, height: 220)
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .shadow(color: .black.opacity(0.4), radius: 16, y: 4)
            .padding(.top, 16)

            if let album {
                VStack(spacing: 6) {
                    Text(album.title)
                        .font(.title2.weight(.bold))
                        .foregroundStyle(Color.mmPrimaryText)
                        .multilineTextAlignment(.center)
                        .lineLimit(2)

                    HStack(spacing: 6) {
                        if let year = album.year {
                            Text(String(year.intValue))
                                .font(.subheadline)
                                .foregroundStyle(Color.mmSecondaryText)
                        }
                    }

                    NavigationLink {
                        ArtistDetailView(
                            graph: graph,
                            artistId: album.artist_id,
                            player: player,
                        )
                    } label: {
                        Text(album.artist_name)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(Color.mmAccentPrimary)
                            .underline(true, color: Color.mmAccentPrimary.opacity(0.4))
                    }
                    .buttonStyle(.plain)
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
            HStack {
                Text("Pistas")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.mmSecondaryText)
                Spacer()
                Text("\(tracks.count)")
                    .font(.caption)
                    .foregroundStyle(Color.mmTextDisabled)
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 8)

            if isLoading {
                ProgressView()
                    .tint(Color.mmAccentPrimary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 24)
            } else if tracks.isEmpty {
                Text("No hay pistas en este álbum")
                    .font(.subheadline)
                    .foregroundStyle(Color.mmSecondaryText)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 24)
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
                    placeholderSeed: album?.title ?? track.albumTitle,
                    placeholder: {
                        CoverArtPlaceholder(
                            seed: album?.title ?? track.albumTitle,
                            systemImage: "music.note",
                            cornerRadius: 4,
                            initial: track.albumTitle.first.map(String.init),
                        )
                    },
                )
                .frame(width: 40, height: 40)

                VStack(alignment: .leading, spacing: 2) {
                    Text(track.title)
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(Color.mmPrimaryText)
                        .lineLimit(1)
                    Text(track.artistName)
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
            let a = try await graph.libraryRepository.albumById(id: albumId)
            await MainActor.run { self.album = a }
        } catch {
            // AlbumDetail gracefully degrades to title-less header.
        }

        // `LibraryQuery`'s Kotlin `Long?` parameters bridge to Swift as
// `KotlinLong?` and the constructor doesn't honour Kotlin defaults
// from Swift, so we use the companion factory [LibraryQuery.forAlbum]
// to scope the result set to a single album without having to spell
// out every parameter. Equivalent Kotlin: `LibraryQuery(albumId = …)`.
let query = LibraryQuery.companion.forAlbum(albumId: albumId, limit: 500)
        let flow = graph.libraryRepository.observeTracks(query: query)
        flow.collect(
            collector: AlbumTracksCollector { mapped in
                Task { @MainActor in
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

private final class AlbumTracksCollector: NSObject, Kotlinx_coroutines_coreFlowCollector {
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