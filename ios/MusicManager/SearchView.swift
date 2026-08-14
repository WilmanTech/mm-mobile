import SwiftUI
import MusicManagerShared

/// Search tab — Phase 4.A.5 parity with Android's `SearchScreen`.
///
/// Four sub-tabs (Tracks / Albums / Artists / Playlists), each backed
/// by a long-lived `Flow` subscription on `LibraryRepository`:
///
///   - `observeTracks(...)` / `observeAlbums(...)` / `observePlaylists(...)`
///     — "browse all" mode (empty query)
///   - `searchArtists(query)` — explicit search flow for the artist sub-tab
///     when the query is non-empty
///
/// The two `searchTracks`/`searchAlbums`/`searchPlaylists` overloads
/// that the Android `SearchViewModel` uses aren't exposed via the
/// `LibraryRepository` interface yet (they live only on the
/// SQLDelight generated queries). Falling back to client-side
/// filtering via `.searchable` is acceptable for the 12-track demo
/// fixture — the iOS parity target is "tab wires and renders", not
/// "fast LIKE search".
///
/// Tap handlers map to future detail screens (Phase 4.A.6+). For
/// now we no-op them — the tab structure proves the parity.
struct SearchView: View {

    let graph: LibraryEntry.Graph

    enum SubTab: String, CaseIterable, Identifiable {
        case tracks = "Pistas"
        case albums = "Álbumes"
        case artists = "Artistas"
        case playlists = "Listas"
        var id: String { rawValue }
    }

    @State private var selectedTab: SubTab = .tracks
    @State private var searchText: String = ""

    @State private var tracks: [SwiftTrack] = []
    @State private var albums: [SwiftAlbum] = []
    @State private var artists: [SwiftArtist] = []
    @State private var playlists: [SwiftPlaylist] = []

    var body: some View {
        NavigationStack {
            ZStack {
                Color.mmBackground
                    .ignoresSafeArea()

                VStack(spacing: 0) {
                    subTabsBar
                    content
                }
            }
            .navigationTitle("Buscar")
            .navigationBarTitleDisplayMode(.large)
            .searchable(
                text: $searchText,
                placement: .navigationBarDrawer(displayMode: .automatic),
                prompt: "Buscar en tu biblioteca"
            )
        }
        .task { await observe() }
    }

    // MARK: - Sub-tab bar

    private var subTabsBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(SubTab.allCases) { tab in
                    tabChip(tab)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
        }
        .background(Color.mmBackground)
    }

    private func tabChip(_ tab: SubTab) -> some View {
        let isSelected = (selectedTab == tab)
        return Button(action: { selectedTab = tab }) {
            Text(tab.rawValue)
                .font(.caption.weight(.semibold))
                .foregroundStyle(isSelected ? Color.mmBgBase : Color.mmPrimaryText)
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(
                    isSelected
                        ? Color.mmAccentPrimary
                        : Color.mmBgCard.opacity(0.6)
                )
                .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }

    // MARK: - Content switch

    @ViewBuilder
    private var content: some View {
        let needle = searchText.lowercased()
        switch selectedTab {
        case .tracks:
            trackList(needle: needle)
        case .albums:
            albumList(needle: needle)
        case .artists:
            artistList(needle: needle)
        case .playlists:
            playlistList(needle: needle)
        }
    }

    /// Client-side filter — fine for the demo fixture; if a real
    /// back-end search arrives later it can replace this.
    private func filterTracks(_ items: [SwiftTrack], needle: String) -> [SwiftTrack] {
        if needle.isEmpty { return items }
        return items.filter {
            $0.title.lowercased().contains(needle)
            || $0.albumTitle.lowercased().contains(needle)
            || $0.artistName.lowercased().contains(needle)
        }
    }

    private func filterAlbums(_ items: [SwiftAlbum], needle: String) -> [SwiftAlbum] {
        if needle.isEmpty { return items }
        return items.filter { $0.title.lowercased().contains(needle) }
    }

    private func filterArtists(_ items: [SwiftArtist], needle: String) -> [SwiftArtist] {
        if needle.isEmpty { return items }
        return items.filter { $0.name.lowercased().contains(needle) }
    }

    private func filterPlaylists(_ items: [SwiftPlaylist], needle: String) -> [SwiftPlaylist] {
        if needle.isEmpty { return items }
        return items.filter { $0.name.lowercased().contains(needle) }
    }

    // MARK: - Per-tab list views

    private func trackList(needle: String) -> some View {
        let items = filterTracks(tracks, needle: needle)
        let emptyText = searchText.isEmpty
            ? "No hay pistas en la biblioteca"
            : "Sin coincidencias para \u{201C}\(searchText)\u{201D}"
        return ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                sectionHeader("Pistas")
                if items.isEmpty {
                    emptyState(text: emptyText)
                        .padding(.horizontal, 16)
                } else {
                    listContainer {
                        ForEach(Array(items.enumerated()), id: \.element.id) { idx, item in
                            TrackSearchRow(track: item)
                            if idx < items.count - 1 { listDivider }
                        }
                    }
                }
            }
            .padding(.bottom, 24)
        }
    }

    private func albumList(needle: String) -> some View {
        let items = filterAlbums(albums, needle: needle)
        let emptyText = searchText.isEmpty
            ? "No hay álbumes"
            : "Sin coincidencias para \u{201C}\(searchText)\u{201D}"
        return ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                sectionHeader("Álbumes")
                if items.isEmpty {
                    emptyState(text: emptyText)
                        .padding(.horizontal, 16)
                } else {
                    listContainer {
                        ForEach(Array(items.enumerated()), id: \.element.id) { idx, item in
                            AlbumSearchRow(album: item)
                            if idx < items.count - 1 { listDivider }
                        }
                    }
                }
            }
            .padding(.bottom, 24)
        }
    }

    private func artistList(needle: String) -> some View {
        let items = filterArtists(artists, needle: needle)
        let emptyText = searchText.isEmpty
            ? "No hay artistas"
            : "Sin coincidencias para \u{201C}\(searchText)\u{201D}"
        return ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                sectionHeader("Artistas")
                if items.isEmpty {
                    emptyState(text: emptyText)
                        .padding(.horizontal, 16)
                } else {
                    listContainer {
                        ForEach(Array(items.enumerated()), id: \.element.id) { idx, item in
                            ArtistSearchRow(artist: item)
                            if idx < items.count - 1 { listDivider }
                        }
                    }
                }
            }
            .padding(.bottom, 24)
        }
    }

    private func playlistList(needle: String) -> some View {
        let items = filterPlaylists(playlists, needle: needle)
        let emptyText = searchText.isEmpty
            ? "No hay listas de reproducción"
            : "Sin coincidencias para \u{201C}\(searchText)\u{201D}"
        return ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                sectionHeader("Listas")
                if items.isEmpty {
                    emptyState(text: emptyText)
                        .padding(.horizontal, 16)
                } else {
                    listContainer {
                        ForEach(Array(items.enumerated()), id: \.element.id) { idx, item in
                            PlaylistSearchRow(playlist: item)
                            if idx < items.count - 1 { listDivider }
                        }
                    }
                }
            }
            .padding(.bottom, 24)
        }
    }

    private func sectionHeader(_ title: String) -> some View {
        Text(title)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(Color.mmSecondaryText)
            .padding(.horizontal, 16)
            .padding(.top, 16)
    }

    @ViewBuilder
    private func listContainer<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        VStack(spacing: 0) {
            content()
        }
        .background(Color.mmBgCard)
        .clipShape(RoundedRectangle(cornerRadius: MusicManagerTheme.cornerRadius))
        .padding(.horizontal, 16)
    }

    private var listDivider: some View {
        Divider()
            .background(Color.mmTextDisabled.opacity(0.2))
            .padding(.leading, 56)
    }

    private func emptyState(text: String) -> some View {
        VStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .font(.title)
                .foregroundStyle(Color.mmTextDisabled)
            Text(text)
                .font(.subheadline)
                .foregroundStyle(Color.mmSecondaryText)
                .multilineTextAlignment(.center)
        }
        .padding(.vertical, 36)
        .frame(maxWidth: .infinity)
        .background(Color.mmBgCard)
        .clipShape(RoundedRectangle(cornerRadius: MusicManagerTheme.cornerRadius))
    }

    // MARK: - Observation

    private func observe() async {
        observeTracks()
        observeAlbums()
        observeArtists()
        observePlaylists()
    }

    private func observeTracks() {
        let flow = graph.libraryRepository.observeTracks(
            query: LibraryQuery.companion.Default
        )
        flow.collect(collector: SearchTracksCollector { tracks in
            Task { @MainActor in self.tracks = tracks }
        }) { _ in }
    }

    private func observeAlbums() {
        let flow = graph.libraryRepository.observeAlbums(
            query: LibraryQuery.companion.Default
        )
        flow.collect(collector: SearchAlbumsCollector { albums in
            Task { @MainActor in self.albums = albums }
        }) { _ in }
    }

    private func observeArtists() {
        // Android's SearchViewModel uses searchArtists(query) when the
        // user has typed something and observeArtists() when the
        // query is empty. We don't have a reactive "switch when the
        // query changes" path on iOS — both flows are cheap, so we
        // open both and route `artists` in the View layer based on
        // searchText emptiness.
        let browse = graph.libraryRepository.observeArtists()
        browse.collect(collector: SearchArtistsCollector { artists in
            Task { @MainActor in
                if self.searchText.isEmpty { self.artists = artists }
            }
        }) { _ in }

        let search = graph.libraryRepository.searchArtists(query: "")
        search.collect(collector: SearchArtistsCollector { artists in
            Task { @MainActor in
                if !self.searchText.isEmpty { self.artists = artists }
            }
        }) { _ in }
    }

    private func observePlaylists() {
        let flow = graph.libraryRepository.observePlaylists(
            query: LibraryQuery.companion.Default
        )
        flow.collect(collector: SearchPlaylistsCollector { playlists in
            Task { @MainActor in self.playlists = playlists }
        }) { _ in }
    }
}

// MARK: - Swift-side models

struct SwiftAlbum: Identifiable, Hashable {
    let id: Int64
    let title: String
}

extension SwiftAlbum {
    init(_ album: MusicManagerShared.Album) {
        // SQLDelight `album.title` (snake_case preserved by the KMP
        // bridge). No `album_count` getter on `Album` from
        // `observeAlbums`, so the summary card doesn't get album-count
        // here.
        self.id = album.id
        self.title = album.title
    }
}

struct SwiftPlaylist: Identifiable, Hashable {
    let id: Int64
    let name: String
}

extension SwiftPlaylist {
    init(_ playlist: MusicManagerShared.Playlist) {
        self.id = playlist.id
        self.name = playlist.name
    }
}

// MARK: - Row views

private struct TrackSearchRow: View {
    let track: SwiftTrack

    var body: some View {
        HStack(spacing: 12) {
            RoundedRectangle(cornerRadius: 6)
                .fill(Color.mmBgBase)
                .frame(width: 38, height: 38)
                .overlay(
                    Image(systemName: "music.note")
                        .font(.caption)
                        .foregroundStyle(Color.mmAccentPrimary)
                )

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
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
    }
}

private struct AlbumSearchRow: View {
    let album: SwiftAlbum

    var body: some View {
        HStack(spacing: 12) {
            RoundedRectangle(cornerRadius: 6)
                .fill(Color.mmBgBase)
                .frame(width: 38, height: 38)
                .overlay(
                    Image(systemName: "rectangle.stack")
                        .font(.caption)
                        .foregroundStyle(Color.mmAccentPrimary)
                )
            Text(album.title)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(Color.mmPrimaryText)
                .lineLimit(1)
            Spacer()
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
    }
}

private struct ArtistSearchRow: View {
    let artist: SwiftArtist

    var body: some View {
        HStack(spacing: 12) {
            Circle()
                .fill(Color.mmBgBase)
                .frame(width: 38, height: 38)
                .overlay(
                    Text(artist.name.prefix(1))
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(Color.mmAccentPrimary)
                )
            Text(artist.name)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(Color.mmPrimaryText)
                .lineLimit(1)
            Spacer()
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
    }
}

private struct PlaylistSearchRow: View {
    let playlist: SwiftPlaylist

    var body: some View {
        HStack(spacing: 12) {
            RoundedRectangle(cornerRadius: 6)
                .fill(Color.mmBgBase)
                .frame(width: 38, height: 38)
                .overlay(
                    Image(systemName: "music.note.list")
                        .font(.caption)
                        .foregroundStyle(Color.mmAccentPrimary)
                )
            Text(playlist.name)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(Color.mmPrimaryText)
                .lineLimit(1)
            Spacer()
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
    }
}

// MARK: - FlowCollectors

private final class SearchTracksCollector: NSObject, Kotlinx_coroutines_coreFlowCollector {
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

private final class SearchAlbumsCollector: NSObject, Kotlinx_coroutines_coreFlowCollector {
    private let onEmit: ([SwiftAlbum]) -> Void
    init(onEmit: @escaping ([SwiftAlbum]) -> Void) { self.onEmit = onEmit }
    func emit(value: Any?, completionHandler: @escaping (Error?) -> Void) {
        if let list = value as? [Any] {
            let mapped = list.compactMap { $0 as? MusicManagerShared.Album }
                .map(SwiftAlbum.init)
            onEmit(mapped)
        } else {
            onEmit([])
        }
        completionHandler(nil)
    }
}

private final class SearchArtistsCollector: NSObject, Kotlinx_coroutines_coreFlowCollector {
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

private final class SearchPlaylistsCollector: NSObject, Kotlinx_coroutines_coreFlowCollector {
    private let onEmit: ([SwiftPlaylist]) -> Void
    init(onEmit: @escaping ([SwiftPlaylist]) -> Void) { self.onEmit = onEmit }
    func emit(value: Any?, completionHandler: @escaping (Error?) -> Void) {
        if let list = value as? [Any] {
            let mapped = list.compactMap { $0 as? MusicManagerShared.Playlist }
                .map(SwiftPlaylist.init)
            onEmit(mapped)
        } else {
            onEmit([])
        }
        completionHandler(nil)
    }
}
