import SwiftUI
import MusicManagerShared

/// Phase 3.B+ parity with Android's `ArtistDetailScreen`. Reached
/// from `LibraryScreen`'s artist chips, `SearchView`'s artist rows,
/// and from any album header.
///
/// Layout: large artist name header + "All albums" discography grid.
/// Tapping an album pushes `AlbumDetailView`.
struct ArtistDetailView: View {

    let graph: LibraryEntry.Graph
    let artistId: Int64
    @ObservedObject var player: AvPlayerEngine

    @State private var artist: MusicManagerShared.Artist?
    @State private var albums: [MusicManagerShared.Album] = []
    @State private var isLoading: Bool = true

    private var baseURL: URL {
        URL(string: "http://\(graph.baseHost):\(graph.basePort)") ?? URL(string: "about:blank")!
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                header
                discographyGrid
            }
        }
        .background(Color.mmBackground.ignoresSafeArea())
        .navigationTitle(artist?.name ?? "Artista")
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
    }

    // MARK: - Header

    private var header: some View {
        VStack(spacing: 12) {
            // Phase 3.B+ (2026-08-14): real artist artwork when
            // `ArtistDto.imagePath` is set, otherwise a gradient
            // placeholder with the artist's first letter.
            CoverArtImage(
                coverPath: artist?.cover_path,
                baseURL: baseURL,
                contentMode: .fill,
                placeholderSeed: artist?.name,
                placeholder: {
                    CoverArtPlaceholder(
                        seed: artist?.name,
                        systemImage: "music.mic",
                        cornerRadius: 9999,
                        initial: artist?.name.first.map(String.init),
                    )
                },
                failure: {
                    CoverArtPlaceholder(
                        seed: artist?.name,
                        systemImage: "music.mic",
                        cornerRadius: 9999,
                        initial: artist?.name.first.map(String.init),
                    )
                },
            )
            .frame(width: 160, height: 160)
            .clipShape(Circle())
            .shadow(color: .black.opacity(0.4), radius: 16, y: 4)
            .padding(.top, 16)

            if let artist {
                VStack(spacing: 4) {
                    Text(artist.name)
                        .font(.title2.weight(.bold))
                        .foregroundStyle(Color.mmPrimaryText)
                        .multilineTextAlignment(.center)
                        .lineLimit(2)

                    Text("\(albums.count) \(albums.count == 1 ? "álbum" : "álbumes")")
                        .font(.subheadline)
                        .foregroundStyle(Color.mmSecondaryText)
                }
                .padding(.horizontal, 24)
            }
        }
        .padding(.bottom, 8)
    }

    // MARK: - Discography

    private var discographyGrid: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Discografía")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Color.mmSecondaryText)
                .padding(.horizontal, 16)

            if isLoading {
                ProgressView()
                    .tint(Color.mmAccentPrimary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 24)
            } else if albums.isEmpty {
                Text("No hay álbumes de este artista")
                    .font(.subheadline)
                    .foregroundStyle(Color.mmSecondaryText)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 24)
                    .background(Color.mmBgCard)
                    .clipShape(RoundedRectangle(cornerRadius: MusicManagerTheme.cornerRadius))
                    .padding(.horizontal, 16)
            } else {
                LazyVGrid(
                    columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)],
                    spacing: 16,
                ) {
                    ForEach(albums, id: \.id) { album in
                        NavigationLink {
                            AlbumDetailView(
                                graph: graph,
                                albumId: album.id,
                                player: player,
                            )
                        } label: {
                            albumGridCell(album: album)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 16)
            }
        }
        .padding(.bottom, 24)
    }

    private func albumGridCell(album: MusicManagerShared.Album) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            CoverArtImage(
                coverPath: album.cover_path,
                baseURL: baseURL,
                contentMode: .fill,
                placeholderSeed: album.title,
                placeholder: {
                    CoverArtPlaceholder(
                        seed: album.title,
                        systemImage: "rectangle.stack",
                        cornerRadius: 8,
                        initial: album.title.first.map(String.init),
                    )
                },
                failure: {
                    CoverArtPlaceholder(
                        seed: album.title,
                        systemImage: "rectangle.stack",
                        cornerRadius: 8,
                        initial: album.title.first.map(String.init),
                    )
                },
            )
            .aspectRatio(1, contentMode: .fit)
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .shadow(color: .black.opacity(0.25), radius: 6, y: 2)

            VStack(alignment: .leading, spacing: 2) {
                Text(album.title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.mmPrimaryText)
                    .lineLimit(2)
                if let year = album.year {
                    Text(String(year.intValue))
                        .font(.caption)
                        .foregroundStyle(Color.mmSecondaryText)
                }
            }
        }
        // Phase 3.B+ (2026-08-14) fix: pin every grid cell to the
        // same total height so the two columns in the LazyVGrid
        // visually align. The user reported "primer elemento mas
        // alto que segundo" — the first cell had a longer text
        // block (year + 2-line title) which stretched its height
        // above the second cell. Forcing `.frame(height:)` plus
        // `.lineLimit(2)` on the title keeps both rows even.
        .frame(height: 220)
    }

    // MARK: - Data loading

    private func load() async {
        isLoading = true
        defer { isLoading = false }

        do {
            let a = try await graph.libraryRepository.artistById(id: artistId)
            await MainActor.run { self.artist = a }
        } catch {
            // Fall through with nil artist; header shows "?"
        }

        let flow = graph.libraryRepository.observeAlbumsByArtist(artistId: artistId)
        flow.collect(
            collector: ArtistAlbumsCollector { mapped in
                Task { @MainActor in
                    // Sort by year (oldest first), then by title. Stable
                    // enough for the demo fixture; the desktop order
                    // isn't important here.
                    self.albums = mapped.sorted { lhs, rhs in
                        let ly = lhs.year?.intValue ?? Int.max
                        let ry = rhs.year?.intValue ?? Int.max
                        if ly != ry { return ly < ry }
                        return lhs.title < rhs.title
                    }
                }
            },
        ) { _ in }
    }
}

private final class ArtistAlbumsCollector: NSObject, Kotlinx_coroutines_coreFlowCollector {
    private let onEmit: ([MusicManagerShared.Album]) -> Void
    init(onEmit: @escaping ([MusicManagerShared.Album]) -> Void) { self.onEmit = onEmit }

    func emit(value: Any?, completionHandler: @escaping (Error?) -> Void) {
        if let list = value as? [Any] {
            let mapped = list.compactMap { $0 as? MusicManagerShared.Album }
            onEmit(mapped)
        } else {
            onEmit([])
        }
        completionHandler(nil)
    }
}