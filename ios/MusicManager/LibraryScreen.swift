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

        let tracksFlow = graph.libraryRepository.observeTracks(query: LibraryQuery.companion.Default)
        let artistsFlow = graph.libraryRepository.observeArtists()

        // Attach collectors. `collect(collector:completionHandler:)` is
        // the only way to subscribe to a Kotlin/Native Flow from Swift
        // (Pitfall #25 in the KMP bootstrap skill).
        tracksFlow.collect(collector: tracksCollector) { _ in }
        artistsFlow.collect(collector: artistsCollector) { _ in }
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
        HStack(spacing: 10) {
            statTile(value: "\(tracks.count)", label: "tracks", systemImage: "music.note")
            statTile(value: "\(uniqueAlbumCount)", label: "albums", systemImage: "rectangle.stack")
            statTile(value: "\(artists.count)", label: "artists", systemImage: "music.mic")
        }
        .frame(maxWidth: .infinity)
    }

    private var uniqueAlbumCount: Int {
        Set(tracks.map { $0.albumId }).count
    }

    private func statTile(value: String, label: String, systemImage: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Image(systemName: systemImage)
                .font(.callout)
                .foregroundStyle(Color.mmAccentPrimary)
            Text(value)
                .font(.title2.weight(.semibold))
                .foregroundStyle(Color.mmPrimaryText)
            Text(label)
                .font(.caption)
                .foregroundStyle(Color.mmSecondaryText)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.mmBgCard)
        .clipShape(RoundedRectangle(cornerRadius: MusicManagerTheme.cornerRadius))
    }

    private var artistsStrip: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Artists")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Color.mmSecondaryText)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 10) {
                    artistChip(id: nil, name: "All", count: tracks.count)
                    ForEach(artists) { artist in
                        artistChip(
                            id: artist.id,
                            name: artist.name,
                            count: tracks.filter { $0.artistId == artist.id }.count
                        )
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func artistChip(id: Int64?, name: String, count: Int) -> some View {
        let isSelected = (selectedArtistId == id)
        return Button(action: { selectedArtistId = id }) {
            HStack(spacing: 8) {
                Circle()
                    .fill(isSelected ? Color.mmAccentPrimary : Color.mmBgCard)
                    .frame(width: 24, height: 24)
                    .overlay {
                        Text(name.prefix(1))
                            .font(.caption.weight(.bold))
                            .foregroundStyle(isSelected ? Color.mmBgBase : Color.mmPrimaryText)
                    }
                VStack(alignment: .leading, spacing: 0) {
                    Text(name)
                        .font(.subheadline.weight(.semibold))
                    Text("\(count) tracks")
                        .font(.caption2)
                        .foregroundStyle(Color.mmSecondaryText)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(
                isSelected
                    ? Color.mmBgCard
                    : Color.mmBgCard.opacity(0.6)
            )
            .overlay {
                RoundedRectangle(cornerRadius: 14)
                    .strokeBorder(
                        isSelected ? Color.mmAccentPrimary : Color.clear,
                        lineWidth: 1.5
                    )
            }
            .clipShape(RoundedRectangle(cornerRadius: 14))
            .foregroundStyle(Color.mmPrimaryText)
        }
        .buttonStyle(.plain)
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
                        // Phase 3.B tap-to-play: tapping a row starts
                        // playback of that track. The SwiftTrack.id is
                        // Int64 (SQLDelight row id) but PlayableTrack.id
                        // is String (because the player URL embeds it as
                        // /api/stream/{id}). Convert at the wire boundary.
                        TrackRow(track: track) {
                            player.play(track: PlayableTrack(
                                id: String(track.id),
                                title: track.title,
                                artistName: track.artistName,
                                albumTitle: track.albumTitle,
                            ))
                        }
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
}

extension SwiftArtist {
    init(_ artist: MusicManagerShared.Artist) {
        // Same snake_case quirk as `SwiftTrack`: the row's columns are
        // declared `id` / `name` and the bridge preserves those names.
        self.id = artist.id
        self.name = artist.name
    }
}

// MARK: - TrackRow

private struct TrackRow: View {
    let track: SwiftTrack
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

    private var trackArtwork: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 6)
                .fill(Color.mmBgBase)
            Image(systemName: "music.note")
                .font(.caption)
                .foregroundStyle(Color.mmAccentPrimary)
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
