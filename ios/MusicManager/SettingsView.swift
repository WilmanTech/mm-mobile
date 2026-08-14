import SwiftUI
import MusicManagerShared

/// Settings tab — Phase 4.A.5 parity with Android's `SettingsScreen`.
///
/// Mirrors the four sections from Android:
///   1. Dispositivo — device name (from `PairingState.Paired`) +
///      library size (`observeTrackCount()`)
///   2. Sincronización — last-sync timestamp + "Sincronizar ahora"
///      (full sync) + "Sincronizar cambios" (delta) buttons
///   3. Cuenta — "Desvincular este dispositivo" (calls
///      `coordinator.unpair()` which clears the token + transitions
///      RootView back to the PairingScreen)
///   4. Acerca de — version + build info
///
/// Reads `SyncCoordinator.state` long-lived so the spinner and
/// timestamp stay in sync after the user taps either sync button.
struct SettingsView: View {

    @EnvironmentObject private var coordinator: AppCoordinator
    let graph: LibraryEntry.Graph

    @State private var trackCount: Int64 = 0
    @State private var syncStateClass: String = "Idle"
    @State private var lastError: String?

    var body: some View {
        NavigationStack {
            ZStack {
                Color.mmBackground
                    .ignoresSafeArea()

                ScrollView {
                    VStack(spacing: 16) {
                        deviceSection
                        syncSection
                        accountSection
                        aboutSection
                    }
                    .padding(.vertical, 16)
                }
            }
            .navigationTitle("Ajustes")
            .navigationBarTitleDisplayMode(.large)
        }
        .task { await observe() }
    }

    // MARK: - Sections

    private var deviceSection: some View {
        SettingsSection(title: "Dispositivo", systemImage: "iphone") {
            KeyValueRow(
                label: "Nombre",
                value: deviceName
            )
            Divider().background(Color.mmTextDisabled.opacity(0.3))
            KeyValueRow(
                label: "Canciones en biblioteca",
                value: "\(trackCount)"
            )
        }
    }

    private var syncSection: some View {
        SettingsSection(title: "Sincronización", systemImage: "arrow.triangle.2.circlepath") {
            KeyValueRow(label: "Estado", value: syncStateLabel)
            Divider().background(Color.mmTextDisabled.opacity(0.3))
            KeyValueRow(label: "Canciones", value: "\(trackCount)")

            if let lastError {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Último error")
                        .font(.caption)
                        .foregroundStyle(Color.mmTextDisabled)
                    Text(lastError)
                        .font(.caption.weight(.medium))
                        .foregroundStyle(Color.mmAccentPrimary)
                        .lineLimit(3)
                }
                .padding(.top, 6)
            }

            HStack(spacing: 10) {
                syncButton(label: "Sincronizar ahora", systemImage: "arrow.clockwise") {
                    Task { await triggerFull() }
                }
                syncButton(label: "Sync incremental", systemImage: "arrow.triangle.2.circlepath") {
                    Task { await triggerDelta() }
                }
            }
            .padding(.top, 8)
        }
    }

    private var accountSection: some View {
        SettingsSection(title: "Cuenta", systemImage: "person.circle") {
            Button(role: .destructive, action: coordinator.unpair) {
                HStack {
                    Image(systemName: "rectangle.portrait.and.arrow.right")
                    Text("Desvincular este dispositivo")
                        .font(.subheadline.weight(.medium))
                    Spacer()
                }
                .padding(.vertical, 10)
            }
            .tint(Color.mmAccentPrimary)
        }
    }

    private var aboutSection: some View {
        SettingsSection(title: "Acerca de", systemImage: "info.circle") {
            KeyValueRow(label: "Versión", value: appVersion)
            Divider().background(Color.mmTextDisabled.opacity(0.3))
            KeyValueRow(label: "Build", value: buildNumber)
            Divider().background(Color.mmTextDisabled.opacity(0.3))
            KeyValueRow(label: "Graph", value: graphFingerprint)
        }
    }

    // MARK: - Section helpers

    private func syncButton(label: String, systemImage: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Image(systemName: systemImage)
                Text(label)
                    .font(.caption.weight(.semibold))
                    .lineLimit(1)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity)
            .background(Color.mmBgBase)
            .clipShape(RoundedRectangle(cornerRadius: 10))
            .overlay(
                RoundedRectangle(cornerRadius: 10)
                    .strokeBorder(Color.mmAccentPrimary, lineWidth: 1)
            )
        }
        .tint(Color.mmAccentPrimary)
    }

    // MARK: - Derived state

    private var deviceName: String {
        if case let .paired(deviceName, _) = coordinator.phase {
            return deviceName
        }
        return "Sin emparejar"
    }

    private var syncStateLabel: String {
        switch syncStateClass {
        case "Running": return "Sincronizando…"
        case "Completed": return "Sincronizado"
        case "Failed": return "Error"
        default: return "Inactivo"
        }
    }

    private var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "—"
    }

    private var buildNumber: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "—"
    }

    /// Stable hash of `graph` — purely cosmetic; the user just needs
    /// to see that the cache exists per-session.
    private var graphFingerprint: String {
        "\(Unmanaged.passUnretained(graph).toOpaque().hashValue)"
    }

    // MARK: - Sync triggers

    private func triggerFull() async {
        let result: Any? = await withCheckedContinuation { cont in
            graph.syncCoordinator.syncFull { state, _ in
                cont.resume(returning: state)
            }
        }
        if let failed = result as? SyncStateFailed {
            await MainActor.run { self.lastError = failed.reason }
        } else {
            await MainActor.run { self.lastError = nil }
        }
    }

    private func triggerDelta() async {
        let result: Any? = await withCheckedContinuation { cont in
            graph.syncCoordinator.syncChanges { state, _ in
                cont.resume(returning: state)
            }
        }
        if let failed = result as? SyncStateFailed {
            await MainActor.run { self.lastError = failed.reason }
        } else {
            await MainActor.run { self.lastError = nil }
        }
    }

    // MARK: - Observation

    private func observe() async {
        // Sync coordinator state
        let syncFlow = graph.syncCoordinator.state
        syncFlow.collect(collector: SyncStateNameCollector { name in
            Task { @MainActor in self.syncStateClass = name }
        }) { _ in }

        // Track count
        let countFlow = graph.libraryRepository.observeTrackCount()
        countFlow.collect(collector: CountCollector { count in
            Task { @MainActor in self.trackCount = count }
        }) { _ in }
    }
}

// MARK: - Section container

private struct SettingsSection<Content: View>: View {
    let title: String
    let systemImage: String
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 8) {
                Image(systemName: systemImage)
                    .font(.subheadline)
                    .foregroundStyle(Color.mmAccentPrimary)
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.mmSecondaryText)
                Spacer()
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 8)

            VStack(spacing: 0) {
                content()
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(Color.mmBgCard)
            .clipShape(RoundedRectangle(cornerRadius: MusicManagerTheme.cornerRadius))
            .padding(.horizontal, 16)
        }
    }
}

private struct KeyValueRow: View {
    let label: String
    let value: String

    var body: some View {
        HStack {
            Text(label)
                .font(.subheadline)
                .foregroundStyle(Color.mmSecondaryText)
            Spacer()
            Text(value)
                .font(.subheadline)
                .foregroundStyle(Color.mmPrimaryText)
                .lineLimit(1)
                .truncationMode(.middle)
        }
        .padding(.vertical, 8)
    }
}

// MARK: - FlowCollectors

private final class SyncStateNameCollector: NSObject, Kotlinx_coroutines_coreFlowCollector {
    private let onEmit: (String) -> Void
    init(onEmit: @escaping (String) -> Void) { self.onEmit = onEmit }
    func emit(value: Any?, completionHandler: @escaping (Error?) -> Void) {
        if value is SyncStateRunning { onEmit("Running"); completionHandler(nil); return }
        if value is SyncStateCompleted { onEmit("Completed"); completionHandler(nil); return }
        if value is SyncStateFailed { onEmit("Failed"); completionHandler(nil); return }
        if value is SyncStateIdle { onEmit("Idle"); completionHandler(nil); return }
        onEmit("Idle")
        completionHandler(nil)
    }
}

private final class CountCollector: NSObject, Kotlinx_coroutines_coreFlowCollector {
    private let onEmit: (Int64) -> Void
    init(onEmit: @escaping (Int64) -> Void) { self.onEmit = onEmit }
    func emit(value: Any?, completionHandler: @escaping (Error?) -> Void) {
        if let n = value as? Int64 { onEmit(n) }
        else if let n = value as? Int { onEmit(Int64(n)) }
        completionHandler(nil)
    }
}
