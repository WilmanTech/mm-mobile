import SwiftUI
import MusicManagerShared

/// Lightweight grid view of all albums in the library. Reached from
/// the LibraryScreen stats tile (album count). Tapping a row pushes
/// `AlbumDetailView`.
struct AlbumsListView: View {

    let graph: LibraryEntry.Graph
    @ObservedObject var player: AvPlayerEngine

    @State private var albums: [MusicManagerShared.Album] = []
    @State private var isLoading: Bool = true

    private var baseURL: URL {
        URL(string: "http://\(graph.baseHost):\(graph.basePort)") ?? URL(string: "about:blank")!
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                if isLoading {
                    ProgressView()
                        .tint(Color.mmAccentPrimary)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 24)
                } else if albums.isEmpty {
                    Text("Sin álbumes en la biblioteca")
                        .font(.subheadline)
                        .foregroundStyle(Color.mmSecondaryText)
                        .padding(.vertical, 24)
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
                                albumTile(album: album)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal, 16)
                }
            }
            .padding(.bottom, miniPlayerBottomPadding)
        }
        .background(Color.mmBackground.ignoresSafeArea())
        .navigationTitle("Álbumes")
        .navigationBarTitleDisplayMode(.inline)
        .task { await observe() }
    }

    private func albumTile(album: MusicManagerShared.Album) -> some View {
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
                Text(album.artist_name)
                    .font(.caption)
                    .foregroundStyle(Color.mmSecondaryText)
                    .lineLimit(1)
            }
        }
    }

    private var miniPlayerBottomPadding: CGFloat {
        switch player.state {
        case .idle, .error: return 0
        case .loading, .playing, .paused: return 64 + 49
        }
    }

    private func observe() async {
        isLoading = true
        defer { isLoading = false }
        let flow = graph.libraryRepository.observeAlbums(query: LibraryQuery.companion.Default)
        flow.collect(
            collector: AlbumsListCollector { mapped in
                Task { @MainActor in
                    self.albums = mapped.sorted { $0.title < $1.title }
                    // Phase 3.B+ (2026-08-14): see the matching block
                    // in ArtistsListView. We lazily fire a one-shot
                    // syncFull() when the local cache is empty so
                    // the user doesn't land on an empty state after
                    // a migration or fresh install.
                    if mapped.isEmpty && self.didTriggerFallbackSync == false {
                        self.didTriggerFallbackSync = true
                        self.runFallbackSync()
                    }
                }
            },
        ) { _ in }
    }

    @State private var didTriggerFallbackSync: Bool = false

    private func runFallbackSync() {
        // See ArtistsListView.runFallbackSync for the [weak self]
        // rationale — SwiftUI View structs don't support weak refs.
        graph.syncCoordinator.syncFull { _, error in
            if let error {
                NSLog("AlbumsListView fallback sync error: %@", String(describing: error))
                return
            }
            DispatchQueue.main.async {
                self.didTriggerFallbackSync = false
            }
        }
    }
}

private final class AlbumsListCollector: NSObject, Kotlinx_coroutines_coreFlowCollector {
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