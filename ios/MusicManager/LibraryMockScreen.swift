import SwiftUI

/// Preview of the post-pairing library experience, populated with the same
/// shape of data the MusicManager backend returns from
/// `/api/library/{artists,albums,tracks,search,stats}`.
///
/// **Why this exists**:
/// Phase 4.A.2 ships the visual identity but the iOS app has no real
/// `LibraryRepository` yet — Phase 2 of mm-mobile is the read-side
/// library layer, and that work lives in the Android Compose code
/// (KMP repository in `shared/`, Android consumes it via Hilt).
///
/// Instead of waiting for the iOS repository wiring (Phase 1+ iOS
/// parity), this screen renders the data the **backend already serves
/// today** so we can:
///   1. Validate the brand palette + glass material look-and-feel in a
///      realistic scrollable list (the PairedScreen placeholder shows
///      only a few static rows).
///   2. Lock down the SwiftUI view shapes (`TrackRow`, `AlbumHeader`,
///      `ArtistChip`) so the eventual iOS repository swap is a pure
///      data-binding replacement.
///
/// **Data source**:
/// Captured on 2026-08-11 from a real MusicManager backend (Python
/// uvicorn on :8765) with a synthetic library of 3 artists
/// (Pink Floyd, Radiohead, Tame Impala), 5 albums, 12 tracks. The
/// shape is byte-identical to what the Swift code will receive when
/// the iOS repository lands; only the field names will be the Swift
/// camelCase translation of the JSON snake_case the backend emits.
///
/// **How to see it**:
/// Open the app, pair (or restore from a previous pair — `Restore` is
/// triggered on init), then tap the **Library** tab in the bottom bar.
/// (Note: the bottom-nav routing is wired in `MusicManagerApp.swift`
/// when this mock is the post-pair destination; for now it's
/// accessible via the `showMockLibrary` flag on `PairedScreen`.)
struct LibraryMockScreen: View {

    @State private var selectedArtist: Artist.ID? = "3" // Radiohead
    @State private var searchText: String = ""
    @ObservedObject var player: AvPlayerEngine

    /// The player engine is optional so the preview sheet in
    /// `PairingScreen` (which is presented before the global engine is
    /// constructed in `RootView`) can still instantiate this view.
    /// When nil, the row taps are no-ops — useful for visual review
    /// when audio playback isn't the point.
    init(player: AvPlayerEngine? = nil) {
        self.player = player ?? AvPlayerEngine(authStorage: AuthStorageBridge())
    }

    private var filteredTracks: [Track] {
        let pool: [Track]
        if let artistId = selectedArtist {
            pool = Track.samples.filter { $0.artistId == artistId }
        } else {
            pool = Track.samples
        }
        if searchText.isEmpty {
            return pool
        }
        let needle = searchText.lowercased()
        return pool.filter {
            $0.title.lowercased().contains(needle) ||
            $0.albumTitle.lowercased().contains(needle) ||
            $0.artistName.lowercased().contains(needle)
        }
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.mmBackground
                    .ignoresSafeArea()

                ScrollView {
                    VStack(spacing: 18) {
                        statsRow
                            .padding(.horizontal, 16)

                        artistsStrip
                            .padding(.horizontal, 16)

                        if let artistId = selectedArtist {
                            albumsHeader(artistId: artistId)
                                .padding(.horizontal, 16)
                            tracksList(forArtistId: artistId)
                                .padding(.horizontal, 16)
                        } else {
                            tracksList(forArtistId: nil)
                                .padding(.horizontal, 16)
                        }
                    }
                    .padding(.vertical, 16)
                }
            }
            .navigationTitle("Library")
            .searchable(text: $searchText, placement: .navigationBarDrawer(displayMode: .automatic), prompt: "Search artists, albums, tracks")
            .navigationBarTitleDisplayMode(.large)
        }
    }

    // MARK: - Stats row

    private var statsRow: some View {
        HStack(spacing: 10) {
            statTile(value: "12", label: "tracks", systemImage: "music.note")
            statTile(value: "5", label: "albums", systemImage: "rectangle.stack")
            statTile(value: "3", label: "artists", systemImage: "music.mic")
        }
        .frame(maxWidth: .infinity)
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

    // MARK: - Artists strip

    private var artistsStrip: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Artists")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Color.mmSecondaryText)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 10) {
                    artistChip(id: nil, name: "All", count: Track.samples.count)
                    ForEach(Artist.samples) { artist in
                        artistChip(
                            id: artist.id,
                            name: artist.name,
                            count: Track.samples.filter { $0.artistId == artist.id }.count
                        )
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func artistChip(id: Artist.ID?, name: String, count: Int) -> some View {
        let isSelected = (selectedArtist == id)
        return Button(action: { selectedArtist = id }) {
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

    // MARK: - Albums header

    private func albumsHeader(artistId: Artist.ID) -> some View {
        let albums = Album.samples.filter { $0.artistId == artistId }
        return VStack(alignment: .leading, spacing: 10) {
            Text("Albums")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Color.mmSecondaryText)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 12) {
                    ForEach(albums) { album in
                        albumCard(album: album)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func albumCard(album: Album) -> some View {
        let base = VStack(alignment: .leading, spacing: 6) {
            ZStack {
                LinearGradient(
                    colors: gradientColors(for: album.title),
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
                Image(systemName: "music.note")
                    .font(.title)
                    .foregroundStyle(.white.opacity(0.7))
            }
            .frame(width: 140, height: 140)
            .clipShape(RoundedRectangle(cornerRadius: 12))

            Text(album.title)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Color.mmPrimaryText)
                .lineLimit(1)
            Text("\(album.trackCount) tracks")
                .font(.caption)
                .foregroundStyle(Color.mmSecondaryText)
        }
        .frame(width: 140, alignment: .leading)

        if #available(iOS 26.0, *) {
            return AnyView(base.glassEffect(.regular, in: RoundedRectangle(cornerRadius: 14)))
        } else {
            return AnyView(base)
        }
    }

    // MARK: - Tracks list

    private func tracksList(forArtistId artistId: Artist.ID?) -> some View {
        let tracks = filteredTracks
        return VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Tracks")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.mmSecondaryText)
                Spacer()
                Text("\(tracks.count)")
                    .font(.caption)
                    .foregroundStyle(Color.mmTextDisabled)
            }

            if tracks.isEmpty {
                emptyState
            } else {
                VStack(spacing: 0) {
                    ForEach(Array(tracks.enumerated()), id: \.element.id) { idx, track in
                        TrackRow(track: track) {
                            player.play(track: PlayableTrack(
                                id: track.id,
                                title: track.title,
                                artistName: track.artistName,
                                albumTitle: track.albumTitle,
                                albumId: "",
                            ))
                        }
                        if idx < tracks.count - 1 {
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

    private var emptyState: some View {
        VStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .font(.title)
                .foregroundStyle(Color.mmTextDisabled)
            Text("No tracks match \u{201C}\(searchText)\u{201D}")
                .font(.subheadline)
                .foregroundStyle(Color.mmSecondaryText)
        }
        .padding(.vertical, 36)
        .frame(maxWidth: .infinity)
        .background(Color.mmBgCard)
        .clipShape(RoundedRectangle(cornerRadius: MusicManagerTheme.cornerRadius))
    }

    // MARK: - Helpers

    /// Deterministic colour mapping so the same album always gets the
    /// same gradient regardless of the data source — keeps the UI
    /// stable while we iterate on the library repository.
    private func gradientColors(for albumTitle: String) -> [Color] {
        let palette: [[Color]] = [
            [.purple, .indigo],
            [.orange, .pink],
            [.teal, .blue],
            [.mint, .cyan],
            [.brown, .red],
        ]
        let hash = abs(albumTitle.hashValue) % palette.count
        return palette[hash]
    }
}

// MARK: - TrackRow

private struct TrackRow: View {
    let track: Track
    let onTap: () -> Void

    init(track: Track, onTap: @escaping () -> Void = {}) {
        self.track = track
        self.onTap = onTap
    }

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

            VStack(alignment: .trailing, spacing: 2) {
                Text(formatDuration(track.durationMs))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(Color.mmSecondaryText)
                if track.isFavorite {
                    Image(systemName: "heart.fill")
                        .font(.caption2)
                        .foregroundStyle(Color.mmAccentPrimary)
                }
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
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

    private func formatDuration(_ ms: Int) -> String {
        let totalSeconds = ms / 1000
        let minutes = totalSeconds / 60
        let seconds = totalSeconds % 60
        return String(format: "%d:%02d", minutes, seconds)
    }
}

// MARK: - Sample data

/// Mirrors the JSON shape the backend returns from `/api/library/artists`,
/// `/api/library/albums`, and `/api/library/tracks`. Captured live from
/// the MusicManager backend on 2026-08-11 (12 tracks, 5 albums, 3 artists).
///
/// The `id` strings are stable identifiers we'll reuse when the real
/// repository lands — switching from these hardcoded arrays to
/// `LibraryRepository.observeArtists()` should not change any view code.
struct Artist: Identifiable, Hashable {
    let id: String
    let name: String
}

struct Album: Identifiable, Hashable {
    let id: String
    let title: String
    let artistId: String
    let trackCount: Int
}

struct Track: Identifiable, Hashable {
    let id: String
    let title: String
    let artistId: String
    let artistName: String
    let albumId: String
    let albumTitle: String
    let durationMs: Int
    let isFavorite: Bool
}

extension Artist {
    /// IDs match the SQLDelight row ids assigned by the backend's scan
    /// for the demo library (Pink Floyd=2, Radiohead=3, Tame Impala=1).
    static let samples: [Artist] = [
        .init(id: "2", name: "Pink Floyd"),
        .init(id: "3", name: "Radiohead"),
        .init(id: "1", name: "Tame Impala"),
    ]
}

extension Album {
    static let samples: [Album] = [
        .init(id: "3", title: "The Dark Side of the Moon", artistId: "2", trackCount: 3),
        .init(id: "2", title: "Wish You Were Here",       artistId: "2", trackCount: 2),
        .init(id: "4", title: "OK Computer",              artistId: "3", trackCount: 3),
        .init(id: "5", title: "Kid A",                    artistId: "3", trackCount: 2),
        .init(id: "1", title: "Currents",                 artistId: "1", trackCount: 2),
    ]
}

extension Track {
    static let samples: [Track] = [
        // Pink Floyd — The Dark Side of the Moon
        .init(id: "5", title: "Speak to Me",           artistId: "2", artistName: "Pink Floyd",  albumId: "3", albumTitle: "The Dark Side of the Moon", durationMs: 1056, isFavorite: false),
        .init(id: "7", title: "Breathe (In the Air)",  artistId: "2", artistName: "Pink Floyd",  albumId: "3", albumTitle: "The Dark Side of the Moon", durationMs: 1056, isFavorite: true),
        .init(id: "6", title: "Time",                  artistId: "2", artistName: "Pink Floyd",  albumId: "3", albumTitle: "The Dark Side of the Moon", durationMs: 1056, isFavorite: false),

        // Pink Floyd — Wish You Were Here
        .init(id: "3", title: "Shine On You Crazy Diamond (Parts I-V)", artistId: "2", artistName: "Pink Floyd", albumId: "2", albumTitle: "Wish You Were Here", durationMs: 1056, isFavorite: false),
        .init(id: "4", title: "Welcome to the Machine", artistId: "2", artistName: "Pink Floyd", albumId: "2", albumTitle: "Wish You Were Here", durationMs: 1056, isFavorite: false),

        // Radiohead — OK Computer
        .init(id: "8",  title: "Airbag",              artistId: "3", artistName: "Radiohead", albumId: "4", albumTitle: "OK Computer", durationMs: 1056, isFavorite: false),
        .init(id: "9",  title: "Paranoid Android",    artistId: "3", artistName: "Radiohead", albumId: "4", albumTitle: "OK Computer", durationMs: 1056, isFavorite: true),
        .init(id: "10", title: "Karma Police",        artistId: "3", artistName: "Radiohead", albumId: "4", albumTitle: "OK Computer", durationMs: 1056, isFavorite: false),

        // Radiohead — Kid A
        .init(id: "11", title: "Everything In Its Right Place", artistId: "3", artistName: "Radiohead", albumId: "5", albumTitle: "Kid A", durationMs: 1056, isFavorite: false),
        .init(id: "12", title: "Idioteque",           artistId: "3", artistName: "Radiohead", albumId: "5", albumTitle: "Kid A", durationMs: 1056, isFavorite: false),

        // Tame Impala — Currents
        .init(id: "1", title: "Let It Happen", artistId: "1", artistName: "Tame Impala", albumId: "1", albumTitle: "Currents", durationMs: 1056, isFavorite: true),
        .init(id: "2", title: "Eventually",   artistId: "1", artistName: "Tame Impala", albumId: "1", albumTitle: "Currents", durationMs: 1056, isFavorite: false),
    ]
}

#Preview {
    LibraryMockScreen(player: AvPlayerEngine(authStorage: AuthStorageBridge()))
}