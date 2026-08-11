import SwiftUI

/// Post-pairing placeholder. Shows the device name and a "unpair" button so
/// the smoke test has a visible post-pairing UI. Real library / search /
/// now-playing tabs come in later phases (Phase 1+ iOS parity).
struct PairedScreen: View {

    @EnvironmentObject private var coordinator: AppCoordinator

    var body: some View {
        NavigationStack {
            Form {
                Section("Connected") {
                    if case let .paired(deviceName, pairedAt) = coordinator.phase {
                        LabeledContent("Device", value: deviceName)
                        LabeledContent("Paired at",
                                       value: pairedAt.formatted(date: .abbreviated,
                                                                 time: .shortened))
                    } else {
                        Text("Awaiting paired state…")
                            .foregroundStyle(.secondary)
                    }
                }

                Section("Backend") {
                    LabeledContent("Host", value: coordinator.host)
                    LabeledContent("Port", value: coordinator.port)
                }

                Section {
                    Button(role: .destructive, action: coordinator.unpair) {
                        Label("Unpair", systemImage: "rectangle.portrait.and.arrow.right")
                            .frame(maxWidth: .infinity)
                    }
                }

                Section("Status") {
                    Text("Library / search / now-playing tabs are not implemented yet — they arrive in Phase 1+ iOS parity. This MVP smoke proves the pairing round-trip and token persistence work end-to-end.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("MusicManager")
        }
    }
}

#Preview {
    PairedScreen()
        .environmentObject(AppCoordinator())
}
