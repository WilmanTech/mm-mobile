import SwiftUI

/// Pre-pairing screen. Lets the user edit the backend host/port and tap
/// "Start pairing" to begin the round-trip against MusicManager. Shows
/// the 4-word code returned by /api/pairing/start and polls
/// /api/pairing/status every 2 seconds — once confirmed, transitions to
/// PairedScreen.
///
/// A bottom **Preview library** button opens `LibraryMockScreen` via
/// `coordinator.showPreview = true`. This is the visual validation
/// surface for the brand palette + glass material until the iOS
/// `LibraryRepository` lands (Phase 1+ iOS parity).
///
/// Visual identity (Phase 4.A.2):
/// - Dark canvas with MM palette tokens (`Color.mmBgBase`).
/// - The "Pair with MusicManager" form uses a tinted `Glass` background so
///   the code card floats above the dark grey like the desktop's
///   floating cards (iOS 26 `.glassEffect`).
/// - The "Start pairing" button uses the brand yellow (`Color.mmAccentPrimary`)
///   via `.tint(...)` rather than SwiftUI's default blue.
struct PairingScreen: View {

    @EnvironmentObject private var coordinator: AppCoordinator
    @State private var showPreview = false

    var body: some View {
        NavigationStack {
            ZStack {
                Color.mmBackground
                    .ignoresSafeArea()

                ScrollView {
                    VStack(spacing: 24) {
                        header

                        formSection
                            .padding(.horizontal, 16)

                        startButton
                            .padding(.horizontal, 16)

                        if case let .pending(_, code, expiresAt) = coordinator.phase {
                            codeCard(code: code, expiresAt: expiresAt)
                                .padding(.horizontal, 16)
                                .transition(.opacity.combined(with: .scale))
                        }

                        if let lastError = coordinator.lastError {
                            errorBanner(lastError)
                                .padding(.horizontal, 16)
                        }

                        previewLibraryButton
                            .padding(.horizontal, 16)
                            .padding(.top, 8)
                    }
                    .padding(.top, 24)
                    .animation(.default, value: isStarting)
                }
            }
            .navigationTitle("MusicManager")
            .navigationBarTitleDisplayMode(.large)
            .sheet(isPresented: $showPreview) {
                LibraryMockScreen()
            }
        }
    }

    // MARK: - Sections

    private var header: some View {
        VStack(spacing: 6) {
            Text("Pair your iPhone")
                .font(.title2.weight(.semibold))
                .foregroundStyle(Color.mmPrimaryText)
            Text("Connect this device to MusicManager running on your Mac.")
                .font(.subheadline)
                .foregroundStyle(Color.mmSecondaryText)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
        }
    }

    private var formSection: some View {
        VStack(spacing: 0) {
            HStack {
                Text("Host")
                    .foregroundStyle(Color.mmSecondaryText)
                Spacer()
                TextField("127.0.0.1", text: Binding(
                    get: { coordinator.host },
                    set: { coordinator.updateBackend(host: $0, port: coordinator.port) }
                ))
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.numbersAndPunctuation)
                .foregroundStyle(Color.mmPrimaryText)
                .multilineTextAlignment(.trailing)
            }
            .padding(.vertical, 14)

            Divider()
                .background(Color.mmTextDisabled.opacity(0.4))

            HStack {
                Text("Port")
                    .foregroundStyle(Color.mmSecondaryText)
                Spacer()
                TextField("8765", text: Binding(
                    get: { coordinator.port },
                    set: { coordinator.updateBackend(host: coordinator.host, port: $0) }
                ))
                .keyboardType(.numberPad)
                .foregroundStyle(Color.mmPrimaryText)
                .multilineTextAlignment(.trailing)
            }
            .padding(.vertical, 14)
        }
        .padding(.horizontal, 16)
        .background(Color.mmBgCard)
        .clipShape(RoundedRectangle(cornerRadius: MusicManagerTheme.cornerRadius))
    }

    @ViewBuilder
    private func codeCard(code: String, expiresAt: Date) -> some View {
        let base = VStack(spacing: 10) {
            Text("Pairing code")
                .font(.caption.weight(.semibold))
                .foregroundStyle(Color.mmSecondaryText)
                .textCase(.uppercase)

            Text(code)
                .font(.system(size: 38, weight: .bold, design: .monospaced))
                .foregroundStyle(Color.mmAccentPrimary)
                .multilineTextAlignment(.center)

            Text("Type this code in your MusicManager desktop, or wait for the desktop to confirm the QR scan.")
                .font(.footnote)
                .foregroundStyle(Color.mmSecondaryText)
                .multilineTextAlignment(.center)

            CountdownText(expiresAt: expiresAt)
        }
        .padding(.vertical, 24)
        .padding(.horizontal, 20)
        .frame(maxWidth: .infinity)

        if #available(iOS 26.0, *) {
            base
                .glassEffect(MusicManagerTheme.glass, in: RoundedRectangle(cornerRadius: MusicManagerTheme.cornerRadius))
        } else {
            base
                .background(Color.mmBgCard)
                .clipShape(RoundedRectangle(cornerRadius: MusicManagerTheme.cornerRadius))
        }
    }

    private func errorBanner(_ message: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(Color.mmAccentHover)
            Text(message)
                .font(.footnote)
                .foregroundStyle(Color.mmTextPrimary)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.mmBgCard)
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    // MARK: - Actions

    private var isStarting: Bool {
        if case .pending = coordinator.phase { return true }
        return false
    }

    private var startButton: some View {
        Button(action: { Task { await coordinator.startPairing() } }) {
            HStack(spacing: 10) {
                Image(systemName: isStarting ? "hourglass" : "qrcode.viewfinder")
                Text(isStarting ? "Waiting for desktop…" : "Start pairing")
                    .font(.body.weight(.semibold))
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 16)
            .foregroundStyle(Color.mmBgBase)
            .background(Color.mmAccentPrimary)
            .clipShape(RoundedRectangle(cornerRadius: MusicManagerTheme.cornerRadius))
        }
        .buttonStyle(.plain)
        .disabled(isStarting)
        .opacity(isStarting ? 0.6 : 1.0)
    }

    /// Bypasses pairing entirely and shows the library mock with
    /// real-shape data captured from the backend. Useful for design
    /// review and to validate the brand palette + glass material
    /// without going through the full pairing flow.
    private var previewLibraryButton: some View {
        Button(action: { showPreview = true }) {
            HStack(spacing: 10) {
                Image(systemName: "eye")
                Text("Preview library (mock data)")
                    .font(.subheadline.weight(.medium))
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .foregroundStyle(Color.mmSecondaryText)
            .background(Color.mmBgCard)
            .clipShape(RoundedRectangle(cornerRadius: MusicManagerTheme.cornerRadius))
        }
        .buttonStyle(.plain)
    }
}

/// Counts down to the pairing session expiry. Shows nothing once expired —
/// the backend will report Expired and the AppCoordinator will surface
/// that as an error state.
private struct CountdownText: View {
    let expiresAt: Date
    @State private var now: Date = .init()

    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        Text("Expires in \(remaining)s")
            .font(.caption2)
            .foregroundStyle(Color.mmTextDisabled)
            .onReceive(timer) { now = $0 }
    }

    private var remaining: Int {
        max(0, Int(expiresAt.timeIntervalSince(now)))
    }
}
