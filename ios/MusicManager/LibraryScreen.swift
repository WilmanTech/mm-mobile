import SwiftUI
import MusicManagerShared

/// Real-wired library screen, fed by the shared KMP `LibraryRepository`
/// (Phase 4.A.4). Replaces the preview-only `LibraryMockScreen` once
/// the app is paired: the data comes from `LibraryEntry.shared.make(...)`
/// which composes a `LibraryRepository` + `SyncCoordinator` pair, plus
/// an in-memory `NativeSqliteDriver` cache that `SyncCoordinator.syncFull()`
/// repopulates from `/api/v1/sync/full` on every launch.
///
/// **Why Swift-side models instead of using `MMSTrack`/`MMSArtist` directly?**
/// `MMSTrack` / `MMSArtist` are SQLDelight-generated immutable records —
/// fine for read access but awkward for SwiftUI's `Identifiable` /
/// `Equatable` requirements and for `.searchable` filtering. The
/// `SwiftTrack` / `SwiftArtist` structs here are the SwiftUI-friendly
/// projection. The mapping is identity-preserving (id is the row id) so
/// swapping back to the Kotlin objects is a single search-and-replace
/// when SQLDelight's Swift bindings stabilise.
///
/// **Why the NSObject collector pattern?** Kotlin `Flow.collect { ... }`
/// doesn't bridge to `AsyncSequence`; see Pitfall #25 in the KMP
/// bootstrap skill and the matching `PairingStateCollector` below
/// `AppCoordinator` — same trick, two flows.
struct LibraryScreen: View {

    let graph: LibraryEntry.Graph
    @ObservedObject var player: AvPlayerEngine

    @State private var artists: [SwiftArtist] = []
    @State private var tracks: [SwiftTrack] = []
    @State private var albums: [MusicManagerShared.Album] = []
    @State private var selectedArtistId: Int64? = nil
    @State private var searchText: String = ""
    @State private var isLoading: Bool = true
    @State private var lastError: String?

    var body: some View {
        NavigationStack {
            ZStack {
                Color.mmBackground
                    .ignoresSafeArea()

                if isLoading {
                    loadingState
                } else {
                    ScrollView {
                        VStack(spacing: 18) {
                            statsRow
                                .padding(.horizontal, 16)

                            artistsStrip
                                .padding(.horizontal, 16)

                            tracksList
                                .padding(.horizontal, 16)
                        }
                        .padding(.vertical, 16)
                        // Reserve space for the mini player overlay in
                        // MainTabView. When the engine is idle this is
                        // 0 so we get the full vertical space; when
                        // a track is loaded it adds 64 + 49 (tab bar)
                        // so the last track row isn't hidden.
                        .padding(.bottom, bottomPaddingForMiniPlayer)
                    }
                }
            }
            .navigationTitle("Library")
            .navigationBarTitleDisplayMode(.large)
            .searchable(text: $searchText, placement: .navigationBarDrawer(displayMode: .automatic), prompt: "Search tracks")
        }
        .task {
            await observe()
        }
    }

    /// Total bottom padding to reserve below the scroll content so the
    /// mini player + tab bar don't hide the last row. Mirrors the
    /// heights used in `MainTabView` and `NowPlayingMiniView`.
    private var bottomPaddingForMiniPlayer: CGFloat {
        switch player.state {
        case .idle, .error: return 0
        case .loading, .playing, .paused: return 64 + 49
        }
    }

    // MARK: - Observation

    /// Subscribe to `observeTracks()` + `observeArtists()` and forward
    /// each emission to `@State` so SwiftUI re-renders. The two flows
    /// are cold and synchronous — calling the `observe*` methods once
    /// returns the Flow object, and we attach a long-lived collector
    /// via `collect(collector:completionHandler:)`. The completion
    /// handler fires only when the flow terminates (never, in practice,
    /// for these repository flows).
    private func observe() async {
        let tracksCollector = TracksCollector { tracks in
            Task { @MainActor in
                self.tracks = tracks
                self.isLoading = false
            }
        }
        let artistsCollector = ArtistsCollector { artists in
            Task { @MainActor in
                self.artists = artists
            }
        }
        let albumsCollector = LibraryAlbumsCollector { albums in
            Task { @MainActor in
                self.albums = albums
            }
        }

        let tracksFlow = graph.libraryRepository.observeTracks(query: LibraryQuery.companion.Default)
        let artistsFlow = graph.libraryRepository.observeArtists()
        let albumsFlow = graph.libraryRepository.observeAlbums(query: LibraryQuery.companion.Default)

        // Attach collectors. `collect(collector:completionHandler:)` is
        // the only way to subscribe to a Kotlin/Native Flow from Swift
        // (Pitfall #25 in the KMP bootstrap skill).
        tracksFlow.collect(collector: tracksCollector) { _ in }
        artistsFlow.collect(collector: artistsCollector) { _ in }
        albumsFlow.collect(collector: albumsCollector) { _ in }
    }

    /// Resolve a track's `albumId` into a cover-art URL by looking up
    /// the album in the observed `albums` list. Returns nil when no
    /// album row has matched yet (album sync in flight) or when the
    /// album has no `cover_path`. The caller (TrackRow) renders the
    /// placeholder when this is nil.
    private func coverURL(for albumId: Int64) -> URL? {
        guard let album = albums.first(where: { $0.id == albumId }) else { return nil }
        return CoverArtURLBuilder.url(
            for: album.cover_path,
            baseURL: URL(string: "http://\(graph.baseHost):\(graph.basePort)") ?? URL(string: "about:blank")!,
        )
    }

    // MARK: - Derived data

    private var filteredTracks: [SwiftTrack] {
        let pool: [SwiftTrack]
        if let artistId = selectedArtistId {
            pool = tracks.filter { $0.artistId == artistId }
        } else {
            pool = tracks
        }
        if searchText.isEmpty { return pool }
        let needle = searchText.lowercased()
        return pool.filter {
            $0.title.lowercased().contains(needle) ||
            $0.albumTitle.lowercased().contains(needle) ||
            $0.artistName.lowercased().contains(needle)
        }
    }

    private var trackCount: Int { filteredTracks.count }

    // MARK: - UI sections

    private var statsRow: some View {
        // Four tiles of equal visual weight:
        //   - tracks count (informational — Library is the track tab
        //     already, so this tile has no navigation target)
        //   - albums count → pushes AlbumsListView
        //   - artists count → pushes ArtistsListView
        //   - shuffle → starts playback with the filtered list as
        //     the queue, engine.isShuffled = true
        //
        // Each navigable tile renders a small chevron on the trailing
        // edge so the navigation affordance is visually obvious —
        // addresses the user note about "X artistas / X albumes
        // deberian funcionar como puente a otras vistas".
        HStack(spacing: 10) {
            statTile(value: "\(tracks.count)", label: "pistas", systemImage: "music.note", chevron: false)

            NavigationLink {
                AlbumsListView(graph: graph, player: player)
            } label: {
                statTile(value: "\(uniqueAlbumCount)", label: "álbumes", systemImage: "rectangle.stack", chevron: true)
            }
            .buttonStyle(.plain)

            NavigationLink {
                ArtistsListView(graph: graph, player: player)
            } label: {
                statTile(value: "\(artists.count)", label: "artistas", systemImage: "music.mic", chevron: true)
            }
            .buttonStyle(.plain)

            Button {
                shuffleAndPlayAll()
            } label: {
                statTile(
                    value: "Aleatoria",
                    label: "todas las pistas",
                    systemImage: "shuffle",
                    chevron: false,
                    accent: true,
                )
            }
            .buttonStyle(.plain)
            .disabled(filteredTracks.isEmpty)
        }
    }

    private var uniqueAlbumCount: Int {
        Set(tracks.map { $0.albumId }).count
    }

    /// One stats tile. When `accent == true`, the leading icon uses
    /// the brand yellow as a background so the Shuffle action stands
    /// out without changing the tile's size or font hierarchy.
    /// When `chevron == true`, a trailing chevron signals that the
    /// tile navigates somewhere.
    private func statTile(value: String, label: String, systemImage: String, chevron: Bool, accent: Bool = false) -> some View {
        HStack(alignment: .top, spacing: 6) {
            VStack(alignment: .leading, spacing: 6) {
                ZStack {
                    Circle()
                        .fill(accent ? Color.mmAccentPrimary.opacity(0.18) : Color.mmBgCard)
                        .frame(width: 28, height: 28)
                    Image(systemName: systemImage)
                        .font(.callout.weight(.semibold))
                        .foregroundStyle(Color.mmAccentPrimary)
                }
                Text(value)
                    .font(.title2.weight(.semibold))
                    .foregroundStyle(Color.mmPrimaryText)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                Text(label)
                    .font(.caption)
                    .foregroundStyle(Color.mmSecondaryText)
                    .lineLimit(1)
            }
            Spacer(minLength: 4)
            if chevron {
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Color.mmTextDisabled)
                    .padding(.top, 4)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.mmBgCard)
        .clipShape(RoundedRectangle(cornerRadius: MusicManagerTheme.cornerRadius))
    }

    private var artistsStrip: some View {
        // Phase 3.B+ polish: artists section redesigned from a
        // horizontal-scroll chip strip into a vertical grid of
        // circular avatars + a "Ver todos" link at the bottom. The
        // horizontal scroll was hard to discover on small screens
        // (iPhone 11) — the user reported "navegación por artistas
        // complicada con scroll horizontal" because the strip
        // visually looks like a category filter rather than a
        // navigation list. The vertical grid is recognisable as a
        // list at a glance, and tapping the tile / the "Ver todos"
        // link both push `ArtistsListView` for full discovery.
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Tus artistas")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.mmSecondaryText)
                Spacer()
                NavigationLink {
                    ArtistsListView(graph: graph, player: player)
                } label: {
                    HStack(spacing: 4) {
                        Text("Ver todos")
                            .font(.caption.weight(.semibold))
                        Image(systemName: "chevron.right")
                            .font(.caption2.weight(.semibold))
                    }
                    .foregroundStyle(Color.mmAccentPrimary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Ver todos los artistas")
            }

            if artists.isEmpty {
                Text("Sin artistas")
                    .font(.subheadline)
                    .foregroundStyle(Color.mmSecondaryText)
            } else {
                LazyVGrid(
                    columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)],
                    spacing: 12,
                ) {
                    // Cap the visible grid to 6 artists (3 rows x 2 cols).
                    // Beyond that the user taps "Ver todos" to reach
                    // ArtistsListView. The cap keeps the library screen
                    // scannable.
                    ForEach(Array(artists.prefix(6))) { artist in
                        NavigationLink {
                            ArtistDetailView(
                                graph: graph,
                                artistId: artist.id,
                                player: player,
                            )
                        } label: {
                            artistGridTile(artist: artist)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// One artist tile in the grid. Uses the same circular initial
    /// placeholder as `ArtistDetailView` for visual consistency.
    ///
    /// **Phase 3.B+ (2026-08-14) fix** — previously the tile's height
    /// was driven by the name label (which wraps to 2 lines for
    /// longer artist names). The grid's two columns then ended up
    /// at different total heights because `LazyVGrid` distributes
    /// rows independently. We now pin the tile to a fixed height
    /// with `.frame(height: 116, alignment: .top)` and lay the
    /// content out top-aligned so every cell is the same size
    /// regardless of name length. The cover circle is exactly
    /// 64pt, the name label is centered horizontally below it.
    private func artistGridTile(artist: SwiftArtist) -> some View {
        VStack(spacing: 8) {
            ZStack {
                Circle()
                    .fill(Color.mmBgCard)
                Text(artist.name.prefix(1).uppercased())
                    .font(.title2.weight(.bold))
                    .foregroundStyle(Color.mmAccentPrimary)
            }
            .frame(width: 64, height: 64)
            .shadow(color: .black.opacity(0.2), radius: 4, y: 2)

            Text(artist.name)
                .font(.caption.weight(.semibold))
                .foregroundStyle(Color.mmPrimaryText)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .frame(height: 96)
    }

    private var tracksList: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Tracks")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.mmSecondaryText)
                Spacer()
                Text("\(trackCount)")
                    .font(.caption)
                    .foregroundStyle(Color.mmTextDisabled)
            }

            if filteredTracks.isEmpty {
                emptyState
            } else {
                VStack(spacing: 0) {
                    ForEach(Array(filteredTracks.enumerated()), id: \.element.id) { idx, track in
                        // Phase 3.B+ queue: tapping a row starts
                        // playback of that track AND seeds the
                        // queue with the filtered list so
                        // next/previous walk the visible library
                        // rather than just swapping single tracks.
                        // The SwiftTrack.id is Int64 (SQLDelight
                        // row id) but PlayableTrack.id is String
                        // (because the player URL embeds it as
                        // /api/stream/{id}). Convert at the wire
                        // boundary.
                        TrackRow(
                            track: track,
                            coverURL: coverURL(for: track.albumId),
                            onTap: {
                                let queue = playableQueue(from: filteredTracks)
                                player.play(
                                    track: PlayableTrack(
                                        id: String(track.id),
                                        title: track.title,
                                        artistName: track.artistName,
                                        albumTitle: track.albumTitle,
                                        albumId: String(track.albumId),
                                    ),
                                    in: queue,
                                )
                            },
                        )
                        if idx < filteredTracks.count - 1 {
                            Divider()
                                .background(Color.mmTextDisabled.opacity(0.2))
                                .padding(.leading, 56)
                        }
                    }
                }
                .background(Color.mmBgCard)
                .clipShape(RoundedRectangle(cornerRadius: MusicManagerTheme.cornerRadius))
            }
        }
    }

    private var loadingState: some View {
        VStack(spacing: 12) {
            ProgressView()
                .controlSize(.large)
                .tint(Color.mmAccentPrimary)
            Text("Loading library…")
                .font(.subheadline)
                .foregroundStyle(Color.mmSecondaryText)
            if let lastError {
                Text(lastError)
                    .font(.caption)
                    .foregroundStyle(Color.mmAccentPrimary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .font(.title)
                .foregroundStyle(Color.mmTextDisabled)
            Text(searchText.isEmpty
                 ? "No tracks in this library yet"
                 : "No tracks match \u{201C}\(searchText)\u{201D}")
                .font(.subheadline)
                .foregroundStyle(Color.mmSecondaryText)
        }
        .padding(.vertical, 36)
        .frame(maxWidth: .infinity)
        .background(Color.mmBgCard)
        .clipShape(RoundedRectangle(cornerRadius: MusicManagerTheme.cornerRadius))
    }

    // MARK: - Playback helpers (Phase 3.B+)

    /// Map SwiftTrack rows into the player's `PlayableTrack` shape.
    /// Centralised here so the row-tap closure and `shuffleAndPlayAll`
    /// stay in sync as the row schema evolves.
    private func playableQueue(from rows: [SwiftTrack]) -> [PlayableTrack] {
        rows.map {
            PlayableTrack(
                id: String($0.id),
                title: $0.title,
                artistName: $0.artistName,
                albumTitle: $0.albumTitle,
                albumId: String($0.albumId),
            )
        }
    }

    /// Shuffle the current filtered list and start playback. Honours
    /// the search and artist-filter chips above the list — tapping
    /// "Shuffle" with an artist selected plays only that artist's
    /// tracks. The engine's `isShuffled` is also turned on so
    /// `next()` keeps surprising the user.
    private func shuffleAndPlayAll() {
        let rows = filteredTracks.shuffled()
        guard !rows.isEmpty else { return }
        let queue = playableQueue(from: rows)
        let first = queue[0]
        player.isShuffled = true
        player.play(track: first, in: queue)
    }
}

// MARK: - Swift-side models

/// SwiftUI-friendly projection of `MMSTrack` (SQLDelight row). The
/// `MMSTrack` record exposes all 14 columns of the `track` table;
/// `SwiftTrack` keeps only what the library screen renders. New
/// columns don't need to be added here until they're shown.
///
/// **Naming note**: `MMSTrack` would collide with the `Track` type
/// already declared in `LibraryMockScreen.swift` (a `struct Track`
/// used for the static preview data). We use `SwiftTrack` to keep
/// the SQLDelight row separate. If/when `LibraryMockScreen` is
/// removed, the `Swift` prefix can be dropped.
struct SwiftTrack: Identifiable, Hashable {
    let id: Int64
    let title: String
    let albumId: Int64
    let albumTitle: String
    let artistId: Int64
    let artistName: String
    let durationMs: Int64
}

extension SwiftTrack {
    init(_ track: MusicManagerShared.Track) {
        // `MusicManagerShared.Track` qualifies the SQLDelight row. Without
        // the qualification Swift would pick the local `struct Track`
        // declared in `LibraryMockScreen.swift` (which has `id: String`
        // and no `album_id` member). The local `Track` is the preview-only
        // sample data; the KMP row is the real data source.
        self.title = track.title
        self.albumId = track.album_id
        self.albumTitle = track.album_title
        self.artistId = track.artist_id
        self.artistName = track.artist_name
        self.durationMs = track.duration_ms
        self.id = track.id
    }
}

/// SwiftUI-friendly projection of `MMSArtist_` (the domain `Artist`
/// class bridged from `domain.model.Artist`). The domain class has
/// camelCase property names — `artist.id`, `artist.name` — unlike the
/// SQLDelight row's snake_case names (`Artist` and `Artist_` resolve to
/// the same domain class via the KMP bridge, because the SQLDelight
/// row would have collided with our local `Artist` struct in
/// `LibraryMockScreen`).
///
/// **Naming note**: this conflicts with `struct Artist` declared in
/// `LibraryMockScreen.swift`. We qualify the KMP import by writing the
/// init parameter type as `Track` (which works for `Track` because no
/// local `Track` struct shadows it) and rely on the type-inferred
/// parameter inside `init` to resolve the right `Artist`. The compiler
/// resolves `artist: Artist` against the local struct, so we explicitly
/// use `MusicManagerShared.Artist` here to disambiguate.
struct SwiftArtist: Identifiable, Hashable {
    let id: Int64
    let name: String
    /// Phase 3.B+ (2026-08-14): mirrored from `Artist.cover_path`
    /// (set from `ArtistDto.imagePath` in the sync upsert). Used by
    /// ArtistsListView + ArtistDetailView to render real artist
    /// artwork via `/api/library/covers/{path}`.
    let coverPath: String?
}

extension SwiftArtist {
    init(_ artist: MusicManagerShared.Artist) {
        // Same snake_case quirk as `SwiftTrack`: the row's columns are
        // declared `id` / `name` and the bridge preserves those names.
        self.id = artist.id
        self.name = artist.name
        self.coverPath = artist.cover_path
    }
}

// MARK: - TrackRow

private struct TrackRow: View {
    let track: SwiftTrack
    let coverURL: URL?
    var onTap: () -> Void = {}

    var body: some View {
        HStack(spacing: 12) {
            trackArtwork

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
        // Phase 3.B tap-to-play: a single tap on the row fires the
        // parent-supplied onTap closure which starts playback via
        // AvPlayerEngine. We keep the default value `{}` so existing
        // call sites that just want to render the row (e.g. embedded
        // previews) don't have to wire it.
        .contentShape(Rectangle())
        .onTapGesture { onTap() }
    }

    /// Album cover art for this track row. We use the row's
    /// `coverURL` (passed by the parent) so the same CoverArtImage
    /// handles placeholder / loading / failure uniformly. The
    /// placeholder is seeded with the track title so neighbouring
    /// tracks get visibly different gradients.
    private var trackArtwork: some View {
        CoverArtImage(
            coverPath: nil,
            baseURL: URL(string: "about:blank")!,
            contentMode: .fill,
            placeholderSeed: track.title,
            placeholder: {
                CoverArtPlaceholder(
                    seed: track.title,
                    systemImage: "music.note",
                    cornerRadius: 6,
                    initial: track.title.first.map(String.init),
                )
            },
            failure: {
                CoverArtPlaceholder(
                    seed: track.title,
                    systemImage: "music.note",
                    cornerRadius: 6,
                    initial: track.title.first.map(String.init),
                )
            },
        )
        .overlay {
            // When we have a real cover URL, layer the AsyncImage on
            // top of the placeholder so the gradient shows through
            // during loading and the placeholder stays visible if
            // the network request fails.
            if let coverURL {
                AsyncImage(url: coverURL) { phase in
                    switch phase {
                    case .empty:
                        Color.clear
                    case .success(let image):
                        image.resizable().scaledToFill()
                    case .failure:
                        Color.clear
                    @unknown default:
                        Color.clear
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: 6))
            }
        }
        .frame(width: 38, height: 38)
    }

    private func formatDuration(_ ms: Int64) -> String {
        let totalSeconds = Int(ms / 1000)
        let minutes = totalSeconds / 60
        let seconds = totalSeconds % 60
        return String(format: "%d:%02d", minutes, seconds)
    }
}

// MARK: - FlowCollectors

private final class TracksCollector: NSObject, Kotlinx_coroutines_coreFlowCollector {
    private let onEmit: ([SwiftTrack]) -> Void

    init(onEmit: @escaping ([SwiftTrack]) -> Void) {
        self.onEmit = onEmit
    }

    func emit(value: Any?, completionHandler: @escaping (Error?) -> Void) {
        if let list = value as? [Any] {
            // The Kotlin Flow<List<MusicManagerShared.Track>> arrives as
            // an NSArray of MMSTrack instances. We compactMap through the
            // fully-qualified type so Swift doesn't pick the local
            // `struct Track` from `LibraryMockScreen.swift` — see the
            // `SwiftTrack.init` doc comment for the full explanation.
            let mapped = list.compactMap { $0 as? MusicManagerShared.Track }.map(SwiftTrack.init)
            onEmit(mapped)
        } else {
            onEmit([])
        }
        completionHandler(nil)
    }
}

private final class ArtistsCollector: NSObject, Kotlinx_coroutines_coreFlowCollector {
    private let onEmit: ([SwiftArtist]) -> Void

    init(onEmit: @escaping ([SwiftArtist]) -> Void) {
        self.onEmit = onEmit
    }

    func emit(value: Any?, completionHandler: @escaping (Error?) -> Void) {
        if let list = value as? [Any] {
            // Same disambiguation as TracksCollector — qualify the KMP
            // type so the local `struct Artist` from
            // `LibraryMockScreen.swift` is not used.
            let mapped = list.compactMap { $0 as? MusicManagerShared.Artist }.map(SwiftArtist.init)
            onEmit(mapped)
        } else {
            onEmit([])
        }
        completionHandler(nil)
    }
}

/// Album collector for LibraryScreen. We only need the rows to
/// resolve `albumId → coverPath` for TrackRow's cover art, so we
/// keep them around as `MusicManagerShared.Album` (the SQLDelight row
/// type) without projecting to a separate Swift type.
private final class LibraryAlbumsCollector: NSObject, Kotlinx_coroutines_coreFlowCollector {
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
