import SwiftUI

/// Post-pairing placeholder. Shows the device name and a "unpair" button
/// so the smoke test has a visible post-pairing UI. Real library / search /
/// now-playing tabs come in later phases (Phase 1+ iOS parity).
struct PairedScreen: View {

    @EnvironmentObject private var coordinator: AppCoordinator

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

                        statusNote
                            .padding(.horizontal, 16)
                    }
                }
            }
            .navigationTitle("Connected")
            .navigationBarTitleDisplayMode(.large)
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
