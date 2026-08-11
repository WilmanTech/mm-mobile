import SwiftUI

/// Post-pairing placeholder. Shows the device name and a "unpair" button
/// so the smoke test has a visible post-pairing UI. Real library / search /
/// now-playing tabs come in later phases (Phase 1+ iOS parity).
///
/// In DEBUG builds (or when `showMockLibrary` is true), tapping
/// **View library** opens `LibraryMockScreen` — a preview of the eventual
/// library experience populated with real-shape data captured from the
/// MusicManager backend on 2026-08-11. This is the visual validation
/// surface for the brand palette + glass material until the iOS
/// `LibraryRepository` lands.
struct PairedScreen: View {

    @EnvironmentObject private var coordinator: AppCoordinator
    @State private var showMockLibrary = false

    var body: some View {
        NavigationStack {
            ZStack {
                Color.mmBackground
                    .ignoresSafeArea()

                ScrollView {
                    VStack(spacing: 20) {
                        connectedCard
                            .padding(.horizontal, 16)
                            .padding(.top, 24)

                        backendCard
                            .padding(.horizontal, 16)

                        unpairButton
                            .padding(.horizontal, 16)

                        viewLibraryButton
                            .padding(.horizontal, 16)

                        statusNote
                            .padding(.horizontal, 16)
                    }
                }
            }
            .navigationTitle("Connected")
            .navigationBarTitleDisplayMode(.large)
            .sheet(isPresented: $showMockLibrary) {
                LibraryMockScreen()
            }
        }
    }

    // MARK: - Sections

    private var connectedCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 10) {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(Color.mmAccentPrimary)
                    .font(.title2)
                Text("Paired")
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(Color.mmPrimaryText)
                Spacer()
            }

            Divider()
                .background(Color.mmTextDisabled.opacity(0.3))

            if case let .paired(deviceName, pairedAt) = coordinator.phase {
                row("Device", deviceName)
                row("Paired at",
                    pairedAt.formatted(date: .abbreviated, time: .shortened))
            } else {
                Text("Awaiting paired state…")
                    .foregroundStyle(Color.mmSecondaryText)
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.mmBgCard)
        .clipShape(RoundedRectangle(cornerRadius: MusicManagerTheme.cornerRadius))
    }

    private var backendCard: some View {
        VStack(spacing: 0) {
            row("Host", coordinator.host)
            Divider()
                .background(Color.mmTextDisabled.opacity(0.3))
            row("Port", coordinator.port)
        }
        .padding(.vertical, 8)
        .padding(.horizontal, 16)
        .background(Color.mmBgCard)
        .clipShape(RoundedRectangle(cornerRadius: MusicManagerTheme.cornerRadius))
    }

    private var unpairButton: some View {
        Button(role: .destructive, action: coordinator.unpair) {
            HStack(spacing: 10) {
                Image(systemName: "rectangle.portrait.and.arrow.right")
                Text("Unpair")
                    .font(.body.weight(.semibold))
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .foregroundStyle(Color.mmAccentPrimary)
            .background(Color.mmBgCard)
            .clipShape(RoundedRectangle(cornerRadius: MusicManagerTheme.cornerRadius))
        }
        .buttonStyle(.plain)
    }

    /// Opens the `LibraryMockScreen` preview. Visible in DEBUG and
    /// Release builds — useful for design review until the iOS
    /// `LibraryRepository` lands. The mock data shape matches the
    /// backend's response byte-for-byte, so swapping it for the real
    /// repository won't require any view changes.
    private var viewLibraryButton: some View {
        Button(action: { showMockLibrary = true }) {
            HStack(spacing: 10) {
                Image(systemName: "music.note.list")
                Text("View library (preview)")
                    .font(.body.weight(.semibold))
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .foregroundStyle(Color.mmPrimaryText)
            .background(Color.mmBgCard)
            .clipShape(RoundedRectangle(cornerRadius: MusicManagerTheme.cornerRadius))
        }
        .buttonStyle(.plain)
    }

    private var statusNote: some View {
        Text("Library / search / now-playing tabs are not implemented yet — they arrive in Phase 1+ iOS parity. This MVP smoke proves the pairing round-trip and token persistence work end-to-end.")
            .font(.footnote)
            .foregroundStyle(Color.mmTextDisabled)
            .multilineTextAlignment(.leading)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func row(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label)
                .foregroundStyle(Color.mmSecondaryText)
            Spacer()
            Text(value)
                .foregroundStyle(Color.mmPrimaryText)
        }
        .padding(.vertical, 10)
    }
}

#Preview {
    PairedScreen()
        .environmentObject(AppCoordinator())
}
