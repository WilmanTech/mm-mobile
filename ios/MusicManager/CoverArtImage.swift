import SwiftUI

/// Loads a cover art image from the MusicManager backend.
///
/// The backend exposes covers via `/api/library/covers/{cover_path:path}`
/// (NOT by album id — by the absolute path on the desktop's
/// filesystem). The path is what comes back in the sync DTOs as
/// `AlbumDto.coverPath` and `ArtistDto.imagePath`.
///
/// **No auth needed**: the endpoint is open on the LAN by design
/// (verified in backend `main.py:1830`). The bearer token only
/// protects library / sync / pairing endpoints.
///
/// **Why not `AsyncImage` directly**: the path arrives URL-encoded by
/// the backend, but `URLComponents` + `addingPercentEncoding` is the
/// safer transformation for paths containing `+`, spaces, or non-ASCII
/// characters (Pink Floyd's "The Wall" folder is a common offender).
/// Wrapping the loader behind a small view means each row can opt in
/// with `CoverArtImage(coverPath: ...)` without re-implementing the
/// URL parsing at every call site.
///
/// **Caching**: `AsyncImage` has its own URLSession-backed cache; we
/// rely on that. For larger libraries we'd add an `NSCache` here, but
/// the 12-track demo fixture doesn't justify it.
struct CoverArtImage<Placeholder: View, Failure: View>: View {

    let coverPath: String?
    let baseURL: URL
    let contentMode: ContentMode
    /// Optional seed used to vary the placeholder gradient. Typically
    /// the album or artist name — `seed`-ed placeholders all share
    /// the same gradient when they share the same seed.
    let placeholderSeed: String?
    let placeholder: () -> Placeholder
    let failure: () -> Failure

    init(
        coverPath: String?,
        baseURL: URL,
        contentMode: ContentMode = .fill,
        placeholderSeed: String? = nil,
        @ViewBuilder placeholder: @escaping () -> Placeholder = { CoverArtPlaceholder() },
        @ViewBuilder failure: @escaping () -> Failure = { CoverArtPlaceholder() },
    ) {
        self.coverPath = coverPath
        self.baseURL = baseURL
        self.contentMode = contentMode
        self.placeholderSeed = placeholderSeed
        self.placeholder = placeholder
        self.failure = failure
    }

    var body: some View {
        if let url = CoverArtURLBuilder.url(for: coverPath, baseURL: baseURL) {
            AsyncImage(url: url, transaction: Transaction(animation: .easeInOut(duration: 0.2))) { phase in
                switch phase {
                case .empty:
                    placeholder()
                case .success(let image):
                    image
                        .resizable()
                        .aspectRatio(contentMode: contentMode)
                case .failure:
                    failure()
                @unknown default:
                    placeholder()
                }
            }
        } else {
            placeholder()
        }
    }
}

/// Placeholder artwork shown by `CoverArtImage` when no cover path is
/// supplied or the URL fails to load.
///
/// **Phase 3.B+ polish** — previously every placeholder looked
/// identical: a flat `Color.mmBgCard` rectangle with a centered
/// `music.note` glyph. When the user's library has many albums /
/// tracks with no embedded artwork (which is the common case for
/// smaller libraries), the UI felt repetitive. This view replaces
/// that with a deterministic gradient chosen from a small palette
/// based on the hash of the supplied `seed` (typically the artist
/// or album title). The gradient + bold initial letter give each
/// placeholder a distinct, recognisable identity, matching the
/// Spotify / Apple Music "missing artwork" pattern.
struct CoverArtPlaceholder: View {

    /// Optional seed used to choose the gradient deterministically.
    /// When nil, the gradient rotates through the palette
    /// sequentially. When set (e.g. an album title or artist name),
    /// the gradient is derived from the string's hash so the same
    /// album always gets the same gradient.
    var seed: String? = nil

    var systemImage: String = "music.note"
    var cornerRadius: CGFloat = 6

    /// Bold overlay character drawn over the gradient. When a seed
    /// is supplied, the first letter uppercased; otherwise nothing.
    var initial: String? = nil

    /// The gradient palette. Six colour pairs cycling through
    /// warm/cool accents so neighbouring placeholders don't look
    /// identical. Each entry is a top→bottom gradient.
    private static let palette: [[Color]] = [
        // Warm
        [Color(red: 0.93, green: 0.36, blue: 0.36), Color(red: 0.78, green: 0.18, blue: 0.45)],
        [Color(red: 0.96, green: 0.55, blue: 0.20), Color(red: 0.85, green: 0.30, blue: 0.40)],
        [Color(red: 0.95, green: 0.75, blue: 0.20), Color(red: 0.78, green: 0.40, blue: 0.30)],
        // Cool
        [Color(red: 0.22, green: 0.55, blue: 0.85), Color(red: 0.45, green: 0.30, blue: 0.78)],
        [Color(red: 0.30, green: 0.72, blue: 0.65), Color(red: 0.15, green: 0.50, blue: 0.55)],
        [Color(red: 0.55, green: 0.40, blue: 0.85), Color(red: 0.35, green: 0.55, blue: 0.92)],
    ]

    /// Stable hash → palette index. Using a simple FNV-1a-style
    /// fold over the UTF-8 bytes so the same string always lands on
    /// the same gradient regardless of seed length.
    private var paletteIndex: Int {
        guard let seed, !seed.isEmpty else { return 0 }
        var h: UInt64 = 0xcbf29ce484222325
        for byte in seed.utf8 {
            h ^= UInt64(byte)
            h = h &* 0x100000001b3
        }
        return Int(h % UInt64(Self.palette.count))
    }

    var body: some View {
        let colors = Self.palette[paletteIndex]
        return ZStack {
            RoundedRectangle(cornerRadius: cornerRadius)
                .fill(
                    LinearGradient(
                        colors: colors,
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing,
                    )
                )
            if let initial, !initial.isEmpty {
                Text(initial.uppercased())
                    .font(.system(size: cornerRadius * 4, weight: .bold))
                    .foregroundStyle(.white.opacity(0.85))
            } else {
                Image(systemName: systemImage)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.white.opacity(0.65))
            }
        }
    }
}

/// Builds the URL the SwiftUI `AsyncImage` should fetch.
///
/// Centralised here so every screen builds the same URL the same way.
/// If the backend ever moves to a `/api/v1/covers/album/{id}` endpoint,
/// this is the one place to swap.
enum CoverArtURLBuilder {

    /// Character set used to percent-encode the filesystem path
    /// before splicing it into the URL. We need a set that permits
    /// spaces (common in album folder names like "The Wall"),
    /// parentheses ("(Deluxe Edition)"), and non-ASCII characters
    /// (Måneskin, Sigur Rós). The strict `urlPathAllowed` set
    /// silently dropped spaces and produced 404s at the backend
    /// because FastAPI never received the real path. Using the
    /// union of `urlPathAllowed` + `urlQueryAllowed` minus the
    /// characters that would close the path segment (`#`/`?`/
    /// `/`/`&`/`+`) keeps the URL well-formed and decodable.
    private static let coverPathAllowed: CharacterSet = {
        var s = CharacterSet.urlPathAllowed.union(.urlQueryAllowed)
        s.remove(charactersIn: "#?&/+")
        return s
    }()

    /// Returns the absolute URL of the cover, or nil if the path is
    /// nil/empty/whitespace. The path is URL-encoded with the
    /// filesystem-friendly character set above.
    static func url(for coverPath: String?, baseURL: URL) -> URL? {
        guard let path = coverPath?.trimmingCharacters(in: .whitespacesAndNewlines),
              !path.isEmpty
        else { return nil }

        // The backend's route is `/api/library/covers/{cover_path:path}`
        // where `cover_path` is the full filesystem path of the cover
        // (e.g. `/Users/Moi/Music/Pink Floyd/The Wall/cover.jpg`).
        // FastAPI decodes the path segment automatically; we just need
        // to URL-encode it so spaces and special chars survive the
        // HTTP round-trip.
        let encodedPath = path.addingPercentEncoding(
            withAllowedCharacters: coverPathAllowed
        ) ?? path

        var components = URLComponents()
        components.scheme = baseURL.scheme
        components.host = baseURL.host
        components.port = baseURL.port
        components.path = "/api/library/covers/\(encodedPath)"
        return components.url
    }
}