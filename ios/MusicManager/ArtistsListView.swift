import SwiftUI
import MusicManagerShared

/// Lightweight grid view of all artists in the library. Reached
/// from the LibraryScreen stats tile (artist count) and from the
/// full artists section. Tapping a row pushes `ArtistDetailView`.
///
/// **Phase 3.B+ (2026-08-14, fix v2)**: the previous
/// `NavigationLink` inside `LazyVGrid` cells silently swallowed
/// taps on iOS 26 — `NavigationLink` plus the grid's lazy cell
/// geometry doesn't reliably resolve the tap gesture. We
/// switched to the value-based `NavigationLink(value:)` API +
/// `.navigationDestination(for: Destination.self)` on the
/// enclosing `NavigationStack`. The `Destination` enum is local
/// to this view (separate from HomeView's, because each view
/// has its own NavigationStack).
struct ArtistsListView: View {

    let graph: LibraryEntry.Graph
    @ObservedObject var player: AvPlayerEngine

    @State private var artists: [SwiftArtist] = []
    @State private var isLoading: Bool = true
    @State private var path: [Destination] = []

    /// Destination enum for `.navigationDestination(for:)` inside
    /// this view's `NavigationStack`. Carries the artist ID so the
    /// detail view can resolve it on push.
    enum Destination: Hashable {
        case artist(Int64)
    }

    private var baseURL: URL {
        URL(string: "http://\(graph.baseHost):\(graph.basePort)") ?? URL(string: "about:blank")!
    }

    var body: some View {
        NavigationStack(path: $path) {
            ScrollView {
                VStack(spacing: 12) {
                    if isLoading {
                        ProgressView()
                            .tint(Color.mmAccentPrimary)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 24)
                    } else if artists.isEmpty {
                        Text("Sin artistas en la biblioteca")
                            .font(.subheadline)
                            .foregroundStyle(Color.mmSecondaryText)
                            .padding(.vertical, 24)
                    } else {
                        LazyVGrid(
                            columns: [
                                GridItem(.flexible(), spacing: 12),
                                GridItem(.flexible(), spacing: 12),
                            ],
                            spacing: 16,
                        ) {
                            ForEach(artists) { artist in
                                NavigationLink(value: Destination.artist(artist.id)) {
                                    artistTile(artist: artist)
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
            .navigationTitle("Artistas")
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(for: Destination.self) { destination in
                switch destination {
                case .artist(let id):
                    ArtistDetailView(graph: graph, artistId: id, player: player)
                }
            }
        }
        .task { await observe() }
    }

    private func artistTile(artist: SwiftArtist) -> some View {
        // Phase 3.B+ (2026-08-14): real artist artwork via the
        // backend's `/api/library/covers/{path}` endpoint, seeded
        // from `ArtistDto.imagePath`. Falls back to a gradient
        // placeholder with the artist's first letter when no
        // artwork is cached yet.
        VStack(spacing: 8) {
            CoverArtImage(
                coverPath: artist.coverPath,
                baseURL: baseURL,
                contentMode: .fill,
                placeholderSeed: artist.name,
                placeholder: {
                    CoverArtPlaceholder(
                        seed: artist.name,
                        systemImage: "music.mic",
                        cornerRadius: 9999,
                        initial: artist.name.first.map(String.init),
                    )
                },
                failure: {
                    CoverArtPlaceholder(
                        seed: artist.name,
                        systemImage: "music.mic",
                        cornerRadius: 9999,
                        initial: artist.name.first.map(String.init),
                    )
                },
            )
            .frame(width: 96, height: 96)
            .clipShape(Circle())
            .shadow(color: .black.opacity(0.2), radius: 4, y: 2)

            Text(artist.name)
                .font(.caption.weight(.semibold))
                .foregroundStyle(Color.mmPrimaryText)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .multilineTextAlignment(.center)
        }
        // Pin to a fixed height so all grid cells are the same
        // vertical size regardless of name length. The grid uses
        // `LazyVGrid` with two columns; without this each cell
        // sized to its own content (circle + 1- or 2-line label)
        // and the columns ended up visually uneven.
        .frame(maxWidth: .infinity)
        .frame(height: 128)
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
        let flow = graph.libraryRepository.observeArtists()
        flow.collect(
            collector: ArtistsListCollector { mapped in
                Task { @MainActor in
                    self.artists = mapped.sorted { $0.name < $1.name }
                    // Phase 3.B+ (2026-08-14): the user reported
                    // "al pulsar en la ficha artists de home se carga
                    // una vista vacia". When the local SQLDelight
                    // cache is empty (e.g. after a schema migration
                    // that wiped data, or a fresh install before the
                    // first sync completes), ArtistsListView would
                    // show an empty state. We trigger a one-shot
                    // `syncFull()` here when the cache comes back
                    // empty, which repopulates the tables and the
                    // observer re-emits with the new rows.
                    if mapped.isEmpty && self.didTriggerFallbackSync == false {
                        self.didTriggerFallbackSync = true
                        self.runFallbackSync()
                    }
                }
            },
        ) { _ in }
    }

    /// Tracks whether we already kicked off a fallback sync for
    /// this view instance. Without this guard the empty-emission
    /// could trigger a sync loop on every observer re-emit (the
    /// SQLDelight flow re-emits whenever the table is touched,
    /// including the post-sync upsert).
    @State private var didTriggerFallbackSync: Bool = false

    /// Trigger `syncFull()` against the backend to repopulate the
    /// local cache when it's empty. Mirrors the same call
    /// `AppCoordinator.rebuildLibraryGraph()` makes on cold launch,
    /// but here we fire it lazily when the user navigates to the
    /// artists list and finds it empty. We re-attach the observer
    /// afterwards so the freshly upserted rows propagate.
    private func runFallbackSync() {
        // No `[weak self]` because SwiftUI `View` structs don't
        // support weak references. The closure captures the struct
        // by value (copy semantics); since the closure body only
        // touches `@State` storage which lives in a backing object
        // outside the struct, the capture is safe and the toggle
        // lands on the live instance via SwiftUI's identity.
        graph.syncCoordinator.syncFull { _, error in
            if let error {
                NSLog("ArtistsListView fallback sync error: %@", String(describing: error))
                return
            }
            // Reset the guard so a future empty emission (e.g. after
            // re-pair in Settings) can trigger another fallback sync.
            DispatchQueue.main.async {
                self.didTriggerFallbackSync = false
            }
        }
    }
}

private final class ArtistsListCollector: NSObject, Kotlinx_coroutines_coreFlowCollector {
    private let onEmit: ([SwiftArtist]) -> Void
    init(onEmit: @escaping ([SwiftArtist]) -> Void) { self.onEmit = onEmit }

    func emit(value: Any?, completionHandler: @escaping (Error?) -> Void) {
        if let list = value as? [Any] {
            let mapped = list.compactMap { $0 as? MusicManagerShared.Artist }
                .map(SwiftArtist.init)
            onEmit(mapped)
        } else {
            onEmit([])
        }
        completionHandler(nil)
    }
}