import SwiftUI

/// Post-pairing screen — Phase 4.A.4 swap.
///
/// When the `AppCoordinator` has built a `LibraryEntry.Graph` (i.e. the
/// app is paired AND the in-memory SQLite cache has been wired up), we
/// render `LibraryScreen` directly so the post-pairing view IS the
/// library — same data the eventual tab bar would show. While the graph
/// is being constructed (the brief window between entering `.paired`
/// and `rebuildLibraryGraph()` returning), we render a thin placeholder
/// with the device/host info so the transition isn't a flash of nothing.
///
/// The "Unpair" button lives at the top-right of the screen instead of
/// a separate card — once the library takes over the main surface, the
/// unpair affordance is a one-tap corner action, not a full-width button
/// taking library real-estate.
struct PairedScreen: View {

    @EnvironmentObject private var coordinator: AppCoordinator

    var body: some View {
        NavigationStack {
            ZStack {
                Color.mmBackground
                    .ignoresSafeArea()

                if let graph = coordinator.libraryGraph {
                    LibraryScreen(graph: graph)
                        .toolbar {
                            ToolbarItem(placement: .topBarTrailing) {
                                unpairButton
                            }
                        }
                } else {
                    pairedPlaceholder
                        .toolbar {
                            ToolbarItem(placement: .topBarTrailing) {
                                unpairButton
                            }
                        }
                }
            }
            .navigationTitle("Connected")
            .navigationBarTitleDisplayMode(.large)
        }
    }

    // MARK: - Placeholder (pre-graph)

    /// Shown for the ~1-frame window between entering `.paired` and the
    /// `LibraryEntry.Graph` being assembled. Contains the same device /
    /// host info that Phase 4.A.2 had on this screen, so nothing in the
    /// user-facing surface disappears during the transition.
    private var pairedPlaceholder: some View {
        ScrollView {
            VStack(spacing: 20) {
                connectedCard
                    .padding(.horizontal, 16)
                    .padding(.top, 24)

                backendCard
                    .padding(.horizontal, 16)
            }
        }
    }

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

    // MARK: - Toolbar unpair

    private var unpairButton: some View {
        Button(role: .destructive, action: coordinator.unpair) {
            Image(systemName: "rectangle.portrait.and.arrow.right")
        }
        .tint(Color.mmAccentPrimary)
    }

    // MARK: - Helpers

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
