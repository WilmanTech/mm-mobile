import SwiftUI

/// Pre-pairing screen. Lets the user edit the backend host/port and tap "Start
/// pairing" to begin the round-trip against MusicManager. Shows the 4-word
/// code returned by /api/pairing/start and polls /api/pairing/status every
/// 2 seconds — once confirmed, transitions to PairedScreen.
struct PairingScreen: View {

    @EnvironmentObject private var coordinator: AppCoordinator

    var body: some View {
        NavigationStack {
            Form {
                Section("Backend") {
                    TextField("Host", text: Binding(
                        get: { coordinator.host },
                        set: { coordinator.updateBackend(host: $0, port: coordinator.port) }
                    ))
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.numbersAndPunctuation)

                    TextField("Port", text: Binding(
                        get: { coordinator.port },
                        set: { coordinator.updateBackend(host: coordinator.host, port: $0) }
                    ))
                    .keyboardType(.numberPad)
                }

                Section {
                    Button(action: { Task { await coordinator.startPairing() } }) {
                        Label("Start pairing", systemImage: "qrcode.viewfinder")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(isStarting)
                }

                if case let .pending(_, code, expiresAt) = coordinator.phase {
                    Section("Pairing code") {
                        VStack(alignment: .center, spacing: 8) {
                            Text(code)
                                .font(.system(.largeTitle, design: .monospaced))
                                .fontWeight(.bold)
                                .multilineTextAlignment(.center)
                            Text("Type this code in your MusicManager desktop, or wait for the desktop to confirm the QR scan.")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                                .multilineTextAlignment(.center)
                            CountdownText(expiresAt: expiresAt)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                    }
                }

                if let lastError = coordinator.lastError {
                    Section("Error") {
                        Text(lastError)
                            .font(.footnote)
                            .foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle("Pair with MusicManager")
        }
    }

    private var isStarting: Bool {
        if case .pending = coordinator.phase { return true }
        return false
    }
}

/// Counts down to the pairing session expiry. Shows nothing once expired —
/// the backend will report Expired and the AppCoordinator will surface that
/// as an error state.
private struct CountdownText: View {
    let expiresAt: Date
    @State private var now: Date = .init()

    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        Text("Expires in \(remaining)s")
            .font(.caption2)
            .foregroundStyle(.tertiary)
            .onReceive(timer) { now = $0 }
    }

    private var remaining: Int {
        max(0, Int(expiresAt.timeIntervalSince(now)))
    }
}
