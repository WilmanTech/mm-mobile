import SwiftUI
import MusicManagerShared

/// Home tab — Phase 3.B+ redesign mirroring Spotify / Apple Music.
///
/// **Phase 3.B+ bug fix v3** — the stats tiles' `NavigationLink`s
/// did not navigate inside the HomeView's `NavigationStack` on
/// iOS 26 (`NavigationLink { AlbumsListView(...) } label: { ... }`
/// inside an `HStack` inside a `ScrollView` swallows the tap
/// without pushing). Fixed by switching to a `NavigationPath` with
/// `.navigationDestination(for:)` — the new pattern handles deep
/// push correctly inside horizontal stacks.
struct HomeView: View {

    let graph: LibraryEntry.Graph
    @ObservedObject var player: AvPlayerEngine

    /// Navigation path for the in-tab `NavigationStack`. We use a
    /// single `NavigationPath` so we can push any destination type
    /// from any view (e.g. the "Tus listas" carousel can push a
    /// `PlaylistDetail` by appending the destination). The
    /// `.navigationDestination(for:)` modifiers below map each
    /// destination type to its view.
    @State private var path = NavigationPath()

    @State private var trackCount: Int64 = 0
    @State private var artists: [SwiftArtist] = []
    @State private var playlists: [MusicManagerShared.Playlist] = []
    @State private var recentTracks: [MusicManagerShared.RecentTrackDto] = []
    @State private var recentAlbums: [MusicManagerShared.RecentAlbumDto] = []

    private var baseURL: URL {
        URL(string: "http://\(graph.baseHost):\(graph.basePort)") ?? URL(string: "about:blank")!
    }

    /// Destination enum for `.navigationDestination(for:)`. Each
    /// case carries the IDs the destination view needs to load.
    enum Destination: Hashable {
        case albumsList
        case artistsList
        case album(Int64)
        case artist(Int64)
        case playlist(Int64)
    }

    var body: some View {
        NavigationStack(path: $path) {
            ZStack {
                Color.mmBackground
                    .ignoresSafeArea()

                ScrollView {
                    VStack(alignment: .leading, spacing: 24) {
                        hero
                            .padding(.horizontal, 16)
                            .padding(.top, 8)

                        summaryCard
                            .padding(.horizontal, 16)

                        if !playlists.isEmpty {
                            playlistsCarousel
                                .padding(.horizontal, 0)
                        }

                        if !recentTracks.isEmpty {
                            recentTracksCarousel
                        }

                        if !recentAlbums.isEmpty {
                            recentAlbumsCarousel
                        }

                        if !artists.isEmpty {
                            artistsGrid
                                .padding(.horizontal, 16)
                        }
                    }
                    .padding(.bottom, miniPlayerBottomPadding)
                }
            }
            .navigationTitle("MusicManager")
            .navigationBarTitleDisplayMode(.large)
            // Map each `Destination` case to its view. Putting all
            // these at the top level of the NavigationStack ensures
            // any push to the path (from any view in the stack)
            // routes correctly, regardless of where the push
            // originated.
            .navigationDestination(for: Destination.self) { destination in
                switch destination {
                case .albumsList:
                    AlbumsListView(graph: graph, player: player)
                case .artistsList:
                    ArtistsListView(graph: graph, player: player)
                case .album(let id):
                    AlbumDetailView(graph: graph, albumId: id, player: player)
                case .artist(let id):
                    ArtistDetailView(graph: graph, artistId: id, player: player)
                case .playlist(let id):
                    PlaylistDetailView(graph: graph, playlistId: id, player: player)
                }
            }
        }
        .task { await observe() }
    }

    // MARK: - Hero

    /// Greets the user with the time-of-day appropriate phrase. We
    /// don't have a user profile API yet, so the name is hardcoded
    /// to "Moi" — the desktop's `device_name` would be a better
    /// source once `/api/v1/devices/me` ships.
    private var hero: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(greeting)
                .font(.title3.weight(.semibold))
                .foregroundStyle(Color.mmPrimaryText)
            Text("Tu música, en cualquier sitio")
                .font(.subheadline)
                .foregroundStyle(Color.mmSecondaryText)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var greeting: String {
        let hour = Calendar.current.component(.hour, from: Date())
        switch hour {
        case 5..<12: return "Buenos días, Moi"
        case 12..<19: return "Buenas tardes, Moi"
        default: return "Buenas noches, Moi"
        }
    }

    // MARK: - Summary card

    private var summaryCard: some View {
        // Four tiles, each one a navigation push to its full-list
        // view (pistas tile stays informational — Library is the
        // tracks tab). The chevron at the trailing edge of every
        // tile makes the navigation affordance explicit, matching
        // Apple Music's stats row pattern.
        HStack(spacing: 10) {
            statTile(value: "\(trackCount)", label: "pistas", systemImage: "music.note", chevron: false)

            Button {
                path.append(Destination.albumsList)
            } label: {
                statTile(value: "\(uniqueAlbumCount)", label: "álbumes", systemImage: "rectangle.stack", chevron: true)
            }
            .buttonStyle(.plain)

            Button {
                path.append(Destination.artistsList)
            } label: {
                statTile(value: "\(artists.count)", label: "artistas", systemImage: "music.mic", chevron: true)
            }
            .buttonStyle(.plain)

            // Shuffle — keeps its own accent background to mark
            // the only non-navigation tile.
            Button {
                shuffleAll()
            } label: {
                statTile(
                    value: "Aleatoria",
                    label: "toda la música",
                    systemImage: "shuffle",
                    chevron: false,
                    accent: true,
                )
            }
            .buttonStyle(.plain)
        }
    }

    private var uniqueAlbumCount: Int {
        Set(recentAlbums.map { $0.id }).count
    }

    /// One stats tile. `chevron: true` renders a small
    /// `chevron.right` at the trailing edge so the user knows the
    /// tile navigates somewhere — addresses the user note "32
    /// artistas, X albumes deberian funcionar como puente a otras
    /// vistas" by making the bridge visually explicit. `accent`
    /// tints the leading icon background for action tiles.
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

    // MARK: - Playlists carousel

    private var playlistsCarousel: some View {
        sectionCarousel(title: "Tus listas") {
            ForEach(Array(playlists.prefix(8)), id: \.id) { playlist in
                Button {
                    path.append(Destination.playlist(playlist.id))
                } label: {
                    playlistTile(playlist: playlist)
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func playlistTile(playlist: MusicManagerShared.Playlist) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            ZStack {
                RoundedRectangle(cornerRadius: 8)
                    .fill(Color.mmBgCard)
                Image(systemName: "music.note.list")
                    .font(.system(size: 36))
                    .foregroundStyle(Color.mmAccentPrimary)
            }
            .aspectRatio(1, contentMode: .fit)
            .shadow(color: .black.opacity(0.25), radius: 6, y: 2)

            Text(playlist.name)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Color.mmPrimaryText)
                .lineLimit(2)
        }
        .frame(width: 160)
    }

    // MARK: - Recent tracks carousel

    private var recentTracksCarousel: some View {
        sectionCarousel(title: "Escuchado recientemente") {
            ForEach(Array(recentTracks.prefix(10)), id: \.id) { track in
                recentTrackButton(track: track)
            }
        }
    }

    /// Single carousel item for recent tracks. Extracted from
    /// `recentTracksCarousel` because the body of that computed
    /// property blew past the SwiftUI type-checker timeout on iOS 26
    /// SDK — nesting the optional-bind + button + label chain
    /// inside a ForEach inside a generic carousel was one inference
    /// step too many.
    private func recentTrackButton(track: MusicManagerShared.RecentTrackDto) -> some View {
        // KMP exposes `Long?` as `KotlinLong?` here; `Destination.album`
        // expects Swift `Int64`. We extract the Int64 conversion up
        // front so the button body only handles the optional path.
        let albumId: Int64? = track.albumId.map { Int64($0.intValue) }
        let trackId: Int64 = track.id
        return Button {
            // Phase 3.B+ (2026-08-14, fix v2): tapping a recent
            // track now reproduces the track directly, with the
            // recent-tracks list as the queue so next/previous
            // jumps between recently-played songs (Apple Music
            // behaviour). Previously we navigated to the source
            // album, but the user reported "Las canciones recientes
            // no hacen nada" — the navigation worked but the user
            // expected playback, matching the home row's tap
            // affordance in the rest of the app.
            playRecentTrack(trackId: trackId)
        } label: {
            recentTrackTile(track: track)
        }
        .buttonStyle(.plain)
        .contextMenu {
            // Long-press → still navigate to the source album for
            // users who want to see the full track listing.
            if let albumId {
                Button {
                    path.append(Destination.album(albumId))
                } label: {
                    Label("Ver álbum", systemImage: "rectangle.stack")
                }
            }
        }
    }

    /// Reproduce a single recent track with the recent-tracks list
    /// as the engine queue. We rebuild the `PlayableTrack` from
    /// the DTO inline (no separate model needed) and queue the
    /// rest of `recentTracks` so next/previous walks the recently
    /// played list.
    private func playRecentTrack(trackId: Int64) {
        let queue = recentTracks.map { dto in
            PlayableTrack(
                id: String(dto.id),
                title: dto.title,
                artistName: dto.artist,
                albumTitle: dto.album,
                albumId: dto.albumId.map { String(Int64($0.intValue)) } ?? "",
            )
        }
        guard let targetIdx = queue.firstIndex(where: { $0.id == String(trackId) }) else {
            return
        }
        let target = queue[targetIdx]
        player.play(track: target, in: queue)
    }

    /// Single track tile (used by recentTracksCarousel).
    ///
    /// The cover-art block is broken into a helper view to keep the
    /// SwiftUI type-checker happy — nesting the full CoverArtImage
    /// configuration inside `recentTrackTile` blew past the
    /// expression-checker timeout on iOS 26 SDK.
    private func recentTrackTile(track: MusicManagerShared.RecentTrackDto) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            recentTrackCover(track: track)
                .aspectRatio(1, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .shadow(color: .black.opacity(0.25), radius: 6, y: 2)

            VStack(alignment: .leading, spacing: 2) {
                Text(track.title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.mmPrimaryText)
                    .lineLimit(1)
                Text("\(track.artist) · \(track.album)")
                    .font(.caption)
                    .foregroundStyle(Color.mmSecondaryText)
                    .lineLimit(1)
            }
        }
        .frame(width: 160)
    }

    /// Cover art for `recentTrackTile`, factored out so the parent
    /// `VStack`'s type-checker expression doesn't blow up. The generic
    /// parameters of `CoverArtImage` (with two placeholder builders)
    /// confuse the type checker when nested inside a `VStack`
    /// body; extracting to a top-level view fixes that.
    private func recentTrackCover(track: MusicManagerShared.RecentTrackDto) -> some View {
        let trackAlbum = track.album
        let trackCover = track.albumCover
        let trackInitial = track.album.first.map(String.init) ?? ""
        return CoverArtImage(
            coverPath: trackCover,
            baseURL: baseURL,
            contentMode: .fill,
            placeholderSeed: trackAlbum,
            placeholder: {
                CoverArtPlaceholder(
                    seed: trackAlbum,
                    systemImage: "music.note",
                    cornerRadius: 8,
                    initial: trackInitial,
                )
            },
            failure: {
                CoverArtPlaceholder(
                    seed: trackAlbum,
                    systemImage: "music.note",
                    cornerRadius: 8,
                    initial: trackInitial,
                )
            },
        )
    }

    // MARK: - Recent albums carousel

    private var recentAlbumsCarousel: some View {
        sectionCarousel(title: "Álbumes recientes") {
            ForEach(Array(recentAlbums.prefix(10)), id: \.id) { album in
                Button {
                    path.append(Destination.album(album.id))
                } label: {
                    recentAlbumTile(album: album)
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func recentAlbumTile(album: MusicManagerShared.RecentAlbumDto) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            CoverArtImage(
                coverPath: album.coverPath,
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
                    .lineLimit(1)
                Text(album.artist)
                    .font(.caption)
                    .foregroundStyle(Color.mmSecondaryText)
                    .lineLimit(1)
            }
        }
        .frame(width: 160)
    }

    // MARK: - Artists grid

    private var artistsGrid: some View {
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
            }

            LazyVGrid(
                columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)],
                spacing: 12,
            ) {
                ForEach(Array(artists.prefix(6))) { artist in
                    Button {
                        path.append(Destination.artist(artist.id))
                    } label: {
                        VStack(spacing: 8) {
                            ZStack {
                                Circle().fill(Color.mmBgCard)
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
                        // Pin the cell to a fixed height so every
                        // tile in the 2-column grid is the same size
                        // regardless of name length.
                        .frame(maxWidth: .infinity)
                        .frame(height: 96)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: - Carousel chrome

    /// Generic horizontal carousel section. `title` is the section
    /// header; the trailing chevron links to the full library tab.
    @ViewBuilder
    private func sectionCarousel<Content: View>(
        title: String,
        @ViewBuilder _ content: () -> Content,
    ) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title)
                .font(.title3.weight(.bold))
                .foregroundStyle(Color.mmPrimaryText)
                .padding(.horizontal, 16)

            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 12) {
                    content()
                }
                .padding(.horizontal, 16)
            }
        }
    }

    // MARK: - Data loading

    private func observe() async {
        // Local SQLDelight cache feeds tracks + artists (always
        // available once sync runs). Recent tracks / albums /
        // playlists come from the backend — they're not in the
        // local DB and require a round-trip per refresh.
        observeLocalCache()
        await fetchRemoteSections()
    }

    private func observeLocalCache() {
        graph.libraryRepository.observeTrackCount()
            .collect(collector: HomeTrackCountCollector { count in
                Task { @MainActor in self.trackCount = count }
            }) { _ in }

        graph.libraryRepository.observeArtists()
            .collect(collector: HomeArtistsCollector { mapped in
                Task { @MainActor in self.artists = mapped }
            }) { _ in }

        graph.libraryRepository.observePlaylists(query: LibraryQuery.companion.Default)
            .collect(collector: HomePlaylistsCollector { mapped in
                Task { @MainActor in self.playlists = mapped }
            }) { _ in }
    }

    private func fetchRemoteSections() async {
        do {
            let tracks = try await graph.api.recentTracks()
            await MainActor.run { self.recentTracks = tracks }
        } catch {
            // Ignore.
        }

        do {
            let albums = try await graph.api.recentAlbums()
            await MainActor.run { self.recentAlbums = albums }
        } catch {
            // Ignore.
        }
    }

    /// Shuffle the full track list as the queue. Reads from the
    /// local SQLDelight cache via `observeTracks` so the user can
    /// shuffle even when the recent-track endpoint is offline.
    private func shuffleAll() {
        graph.libraryRepository.observeTracks(query: LibraryQuery.companion.Default)
            .collect(collector: HomeShuffleCollector { tracks in
                Task { @MainActor in
                    let playable = tracks.map { track in
                        // SQLDelight row uses snake_case for the
                        // joined columns. `MusicManagerShared.Track`
                        // is the raw row, not the SwiftUI-friendly
                        // `SwiftTrack` projection.
                        PlayableTrack(
                            id: String(track.id),
                            title: track.title,
                            artistName: track.artist_name,
                            albumTitle: track.album_title,
                            albumId: String(track.album_id),
                        )
                    }
                    let queue = playable.shuffled()
                    guard let first = queue.first else { return }
                    player.isShuffled = true
                    player.play(track: first, in: queue)
                }
            }) { _ in }
    }

    // MARK: - Layout helpers

    private var miniPlayerBottomPadding: CGFloat {
        switch player.state {
        case .idle, .error: return 0
        case .loading, .playing, .paused: return 64 + 49
        }
    }
}

// MARK: - Flow collectors

private final class HomeTrackCountCollector: NSObject, Kotlinx_coroutines_coreFlowCollector {
    private let onEmit: (Int64) -> Void
    init(onEmit: @escaping (Int64) -> Void) { self.onEmit = onEmit }
    func emit(value: Any?, completionHandler: @escaping (Error?) -> Void) {
        if let n = value as? Int64 {
            onEmit(n)
        } else if let n = value as? Int {
            onEmit(Int64(n))
        }
        completionHandler(nil)
    }
}

private final class HomeArtistsCollector: NSObject, Kotlinx_coroutines_coreFlowCollector {
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

private final class HomePlaylistsCollector: NSObject, Kotlinx_coroutines_coreFlowCollector {
    private let onEmit: ([MusicManagerShared.Playlist]) -> Void
    init(onEmit: @escaping ([MusicManagerShared.Playlist]) -> Void) { self.onEmit = onEmit }
    func emit(value: Any?, completionHandler: @escaping (Error?) -> Void) {
        if let list = value as? [Any] {
            let mapped = list.compactMap { $0 as? MusicManagerShared.Playlist }
            onEmit(mapped)
        } else {
            onEmit([])
        }
        completionHandler(nil)
    }
}

/// One-shot collector for the home screen's shuffle-all action.
/// We take the first emission and ignore subsequent ones — the
/// track list doesn't change between the user tapping "Shuffle" and
/// the next sync, so we don't need a long-lived subscription.
private final class HomeShuffleCollector: NSObject, Kotlinx_coroutines_coreFlowCollector {
    private let onEmit: ([MusicManagerShared.Track]) -> Void
    private var hasEmitted = false
    init(onEmit: @escaping ([MusicManagerShared.Track]) -> Void) { self.onEmit = onEmit }

    func emit(value: Any?, completionHandler: @escaping (Error?) -> Void) {
        if !hasEmitted, let list = value as? [Any] {
            hasEmitted = true
            let mapped = list.compactMap { $0 as? MusicManagerShared.Track }
            onEmit(mapped)
        }
        completionHandler(nil)
    }
}