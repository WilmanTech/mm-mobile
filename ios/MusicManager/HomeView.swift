import SwiftUI
import MusicManagerShared

/// Home tab — Phase 4.A.5 parity with Android's `HomeScreen`.
///
/// Mirrors the Spotify-style landing:
///   - "MusicManager" hero header
///   - "Tu música, en cualquier sitio" subtitle
///   - Library summary card (tracks / albums / artists)
///   - "Artistas" row with circular avatars
///
/// Reads exclusively from `LibraryEntry.Graph` so the data stays
/// reactive — when `SyncCoordinator.syncFull()` repopulates the
/// in-memory SQLite cache, both `observeTrackCount()` and
/// `observeArtists()` re-emit and the view re-renders.
struct HomeView: View {

    let graph: LibraryEntry.Graph

    @State private var trackCount: Int64 = 0
    @State private var artists: [SwiftArtist] = []
    /// Cached album-id snapshot — populated by `observeTracks()` so the
    /// summary card doesn't need a second `observeAlbums()` subscription
    /// (which the KMP bridge doesn't expose yet — `album_count` lives on
    /// the `artist` table in SQLDelight). Same proxy as Android's
    /// `HomeViewModel`, which sums `artist.album_count`.
    @State private var albumIds: [Int64] = []

    var body: some View {
        NavigationStack {
            ZStack {
                Color.mmBackground
                    .ignoresSafeArea()

                ScrollView {
                    VStack(alignment: .leading, spacing: 18) {
                        header
                            .padding(.horizontal, 16)
                            .padding(.top, 8)

                        summaryCard
                            .padding(.horizontal, 16)

                        if !artists.isEmpty {
                            artistsSection
                                .padding(.horizontal, 16)
                        }
                    }
                    .padding(.bottom, 24)
                }
            }
            .navigationTitle("MusicManager")
            .navigationBarTitleDisplayMode(.large)
        }
        .task { await observe() }
    }

    // MARK: - Header

    private var header: some View {
        Text("Tu música, en cualquier sitio")
            .font(.subheadline)
            .foregroundStyle(Color.mmSecondaryText)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: - Summary card

    /// Counts the number of unique album ids among the loaded tracks.
    private var albumCount: Int {
        Set(albumIds).count
    }

    private var summaryCard: some View {
        HStack(spacing: 0) {
            statColumn(count: trackCount, label: "canciones")
            verticalDivider
            statColumn(count: Int64(albumCount), label: "álbumes")
            verticalDivider
            statColumn(count: Int64(artists.count), label: "artistas")
        }
        .padding(.vertical, 14)
        .padding(.horizontal, 8)
        .background(Color.mmBgCard)
        .clipShape(RoundedRectangle(cornerRadius: MusicManagerTheme.cornerRadius))
    }

    private var verticalDivider: some View {
        Rectangle()
            .fill(Color.mmTextDisabled.opacity(0.3))
            .frame(width: 1, height: 36)
    }

    private func statColumn(count: Int64, label: String) -> some View {
        VStack(spacing: 4) {
            Text(formatCount(count))
                .font(.title2.weight(.bold))
                .foregroundStyle(Color.mmPrimaryText)
            Text(label)
                .font(.caption)
                .foregroundStyle(Color.mmSecondaryText)
        }
        .frame(maxWidth: .infinity)
    }

    private func formatCount(_ count: Int64) -> String {
        if count >= 1000 {
            let thousands = Double(count) / 1000.0
            return String(format: "%.1fk", thousands)
        }
        return "\(count)"
    }

    // MARK: - Artists row

    private var artistsSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Artistas")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Color.mmSecondaryText)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .top, spacing: 12) {
                    ForEach(artists) { artist in
                        avatar(name: artist.name)
                    }
                }
            }
        }
    }

    private func avatar(name: String) -> some View {
        VStack(spacing: 6) {
            ZStack {
                Circle()
                    .fill(Color.mmBgCard)
                Text(name.prefix(1))
                    .font(.title3.weight(.bold))
                    .foregroundStyle(Color.mmAccentPrimary)
            }
            .frame(width: 64, height: 64)
            Text(name)
                .font(.caption)
                .foregroundStyle(Color.mmPrimaryText)
                .lineLimit(1)
                .frame(maxWidth: 72)
        }
    }

    // MARK: - Observation

    /// Subscribe to the three flows long-lived. Re-runs whenever the
    /// KMP SQLDelight cache changes (post-syncFull).
    private func observe() async {
        let countFlow = graph.libraryRepository.observeTrackCount()
        countFlow.collect(collector: CountCollector { count in
            Task { @MainActor in self.trackCount = count }
        }) { _ in }

        let artistsFlow = graph.libraryRepository.observeArtists()
        artistsFlow.collect(collector: HomeArtistsCollector { artists in
            Task { @MainActor in self.artists = artists }
        }) { _ in }

        let tracksFlow = graph.libraryRepository.observeTracks(
            query: LibraryQuery.companion.Default
        )
        tracksFlow.collect(collector: AlbumIdsCollector { ids in
            Task { @MainActor in self.albumIds = ids }
        }) { _ in }
    }
}

// MARK: - FlowCollectors

private final class CountCollector: NSObject, Kotlinx_coroutines_coreFlowCollector {
    private let onEmit: (Int64) -> Void
    init(onEmit: @escaping (Int64) -> Void) { self.onEmit = onEmit }
    func emit(value: Any?, completionHandler: @escaping (Error?) -> Void) {
        if let n = value as? Int64 { onEmit(n) }
        else if let n = value as? Int { onEmit(Int64(n)) }
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

/// Bridges `Flow<List<MusicManagerShared.Track>>` to a snapshot of
/// `album_id`s — used by the summary card's album-count proxy.
private final class AlbumIdsCollector: NSObject, Kotlinx_coroutines_coreFlowCollector {
    private let onEmit: ([Int64]) -> Void
    init(onEmit: @escaping ([Int64]) -> Void) { self.onEmit = onEmit }
    func emit(value: Any?, completionHandler: @escaping (Error?) -> Void) {
        if let list = value as? [Any] {
            let ids = list.compactMap { ($0 as? MusicManagerShared.Track)?.album_id }
            onEmit(ids)
        } else {
            onEmit([])
        }
        completionHandler(nil)
    }
}
