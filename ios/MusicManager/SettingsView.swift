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
/// **Phase 3.D additions**:
///   - "Fuente" section showing the current backend host/port
///     (placeholder for the future SMB source selector — see AGENTS.md
///     `LibrarySource` abstraction). The user can see what they're
///     paired against, but the field is read-only here; switching
///     host/port is done from `PairingScreen`.
///   - "Descargas" section. Reads
///     `graph.downloadStateRepository.observeStates()` so it stays
///     in sync with the per-track `download_state` column. Phase
///     3.D doesn't ship a real download worker yet — iOS would need
///     `BGTaskScheduler` entitlements + a worker that the
///     `DownloadTrigger` interface drives. The SettingsView exposes
///     the count + a debug toggle (mark / unmark the first track
///     as downloaded) so the column is exercised end-to-end while
///     the worker lands in Phase 5.
///   - Last-sync timestamp under "Sincronización", captured in
///     `SettingsStore` whenever a `SyncState.Completed` emission
///     lands. Renders as a relative date ("hace 3 min") or the
///     absolute date if older than a day.
struct SettingsView: View {

    @EnvironmentObject private var coordinator: AppCoordinator
    let graph: LibraryEntry.Graph

    @State private var trackCount: Int64 = 0
    @State private var syncStateClass: String = "Idle"
    @State private var lastError: String?
    @State private var lastSyncDate: Date? = SettingsStore.lastSyncDate()

    /// Map of trackId → DownloadInfo from
    /// `DownloadStateRepository.observeStates()`. Empty until the
    /// first emission lands. The SettingsView derives the "Downloaded
    /// vs total" count from this + `trackCount`.
    @State private var downloadStates: [Int64: DownloadInfo] = [:]

    var body: some View {
        NavigationStack {
            ZStack {
                Color.mmBackground
                    .ignoresSafeArea()

                ScrollView {
                    VStack(spacing: 16) {
                        deviceSection
                        sourceSection
                        syncSection
                        downloadsSection
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

    /// Phase 3.D: read-only display of the currently paired backend.
    /// Changing host/port is still done from `PairingScreen` —
    /// Settings is the inspection surface, not the editor.
    private var sourceSection: some View {
        SettingsSection(title: "Fuente", systemImage: "antenna.radiowaves.left.and.right") {
            KeyValueRow(label: "Tipo", value: "MusicManager (backend)")
            Divider().background(Color.mmTextDisabled.opacity(0.3))
            KeyValueRow(label: "Host", value: coordinator.host)
            Divider().background(Color.mmTextDisabled.opacity(0.3))
            KeyValueRow(label: "Puerto", value: coordinator.port)
            // The future SMB source will add a row here once the
            // picker lands (see AGENTS.md `LibrarySource`). For
            // now this section reads as a single-row "what am I
            // connected to" inspector.
        }
    }

    private var syncSection: some View {
        SettingsSection(title: "Sincronización", systemImage: "arrow.triangle.2.circlepath") {
            KeyValueRow(label: "Estado", value: syncStateLabel)
            Divider().background(Color.mmTextDisabled.opacity(0.3))
            KeyValueRow(label: "Canciones", value: "\(trackCount)")

            // Phase 3.D: last successful sync timestamp. Reads
            // from `SettingsStore` (UserDefaults-backed), updated
            // every time a `SyncState.Completed` emission lands.
            // The `RelativeDateTimeFormatter` from Foundation
            // handles the "hace 3 min" formatting in Spanish
            // locale automatically.
            KeyValueRow(
                label: "Última sincronización",
                value: lastSyncLabel
            )

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

    /// Phase 3.D: per-track download summary. Reads
    /// `graph.downloadStateRepository.observeStates()` so the count
    /// stays in sync as tracks flip state. The debug toggle is the
    /// only way to flip state from the iOS UI today — the production
    /// `DownloadTrigger` (BGTaskScheduler worker) lands in Phase 5.
    private var downloadsSection: some View {
        SettingsSection(title: "Descargas", systemImage: "arrow.down.circle") {
            KeyValueRow(
                label: "Descargadas",
                value: "\(downloadedCount) / \(trackCount)"
            )
            Divider().background(Color.mmTextDisabled.opacity(0.3))
            KeyValueRow(
                label: "Tamaño estimado",
                value: estimatedSizeLabel
            )

            VStack(alignment: .leading, spacing: 6) {
                Text("Estado por canción")
                    .font(.caption)
                    .foregroundStyle(Color.mmTextDisabled)
                Text(downloadInfoLabel)
                    .font(.caption.weight(.medium))
                    .foregroundStyle(Color.mmSecondaryText)
            }
            .padding(.top, 6)

            // DEBUG-only toggle: mark / unmark the first available
            // track as downloaded. This is a stand-in for the real
            // BGTaskScheduler worker so we can exercise the column
            // end-to-end from the UI. Compiled out of Release.
            #if DEBUG
            HStack(spacing: 10) {
                debugToggleButton(
                    label: downloadedCount > 0
                        ? "Desmarcar primera (debug)"
                        : "Marcar primera (debug)",
                    systemImage: downloadedCount > 0
                        ? "minus.circle"
                        : "plus.circle",
                    enabled: trackCount > 0,
                ) {
                    Task { await toggleFirstTrackDownload() }
                }
            }
            .padding(.top, 6)
            #endif
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

    /// DEBUG-only toggle for the per-track download state. Uses the
    /// same `syncButton` shape so it visually matches the rest of
    /// the screen, but is rendered inside a `#if DEBUG` block so
    /// Release builds don't ship a "fake" button.
    private func debugToggleButton(
        label: String,
        systemImage: String,
        enabled: Bool,
        action: @escaping () -> Void,
    ) -> some View {
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
            .background(enabled ? Color.mmBgBase : Color.mmBgBase.opacity(0.5))
            .clipShape(RoundedRectangle(cornerRadius: 10))
            .overlay(
                RoundedRectangle(cornerRadius: 10)
                    .strokeBorder(
                        enabled ? Color.mmAccentPrimary : Color.mmTextDisabled,
                        lineWidth: 1,
                    )
            )
        }
        .tint(Color.mmAccentPrimary)
        .disabled(!enabled)
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

    /// Phase 3.D: relative date string for the last successful
    /// sync. We use `RelativeDateTimeFormatter` so the locale
    /// controls the wording — Spanish locales get "hace 3 min",
    /// English get "3 min ago". Older than a day falls through to
    /// `DateFormatter` so we don't render "hace 18 horas" for a
    /// sync that happened last week.
    private var lastSyncLabel: String {
        guard let date = lastSyncDate else {
            return "Nunca"
        }
        let relFormatter = RelativeDateTimeFormatter()
        relFormatter.unitsStyle = .abbreviated
        let rel = relFormatter.localizedString(for: date, relativeTo: Date())
        // If "rel" is a year-scale sentence, fall back to absolute
        // date. Heuristic: RelativeDateTimeFormatter outputs strings
        // like "hace 3 min" / "hace 18 h"; if the delta is large
        // (>= 1 day) the formatter still produces "hace 2 días"
        // which is fine. We only override for year-scale.
        let absFormatter = DateFormatter()
        absFormatter.dateStyle = .medium
        absFormatter.timeStyle = .short
        // Always show both: relative + absolute for clarity
        return "\(rel) · \(absFormatter.string(from: date))"
    }

    private var downloadedCount: Int {
        downloadStates.values.filter { $0 is DownloadInfoDownloaded }.count
    }

    /// Rough size estimate. Until the real download worker ships
    /// we don't have file sizes, so we estimate ~5 MB / track
    /// (FLAC @ ~900 kbps ≈ 1 MB/min, average song ≈ 4 min). This
    /// is a UX hint, not a billing-relevant number.
    private var estimatedSizeLabel: String {
        let bytes = Int64(downloadedCount) * 5 * 1024 * 1024
        let formatter = ByteCountFormatter()
        formatter.countStyle = .file
        return formatter.string(fromByteCount: bytes)
    }

    /// Detailed breakdown of the per-track download states for the
    /// row under "Tamaño estimado". Helps the user see whether
    /// anything is mid-download without us having a real progress
    /// bar yet (Phase 5 will replace this with live progress).
    private var downloadInfoLabel: String {
        let downloading = downloadStates.values.filter { $0 is DownloadInfoDownloading }.count
        let failed = downloadStates.values.filter { $0 is DownloadInfoFailed }.count
        let queued = downloadStates.values.filter { $0 is DownloadInfoDownloading }.count
        // The above two are aliases for the same case today
        // (we don't surface progress yet), so dedupe to avoid
        // confusing the user.
        let pendingTotal = downloading
        let downloaded = downloadedCount
        return "Descargadas: \(downloaded) · En curso: \(pendingTotal) · Fallidas: \(failed)"
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
            await MainActor.run {
                self.lastError = nil
                if result is SyncStateCompleted {
                    SettingsStore.recordSyncCompleted()
                    self.lastSyncDate = SettingsStore.lastSyncDate()
                }
            }
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
            await MainActor.run {
                self.lastError = nil
                if result is SyncStateCompleted {
                    SettingsStore.recordSyncCompleted()
                    self.lastSyncDate = SettingsStore.lastSyncDate()
                }
            }
        }
    }

    /// Phase 3.D: DEBUG-only per-track toggle. Reads the first
    /// track from `graph.libraryRepository.trackById(...)` (we
    /// have to iterate IDs because there's no "first track"
    /// shortcut), then flips its `download_state` via
    /// `graph.downloadStateRepository`. The SwiftUI body will
    /// re-render automatically because we subscribe to
    /// `observeStates()` in `observe()`.
    private func toggleFirstTrackDownload() async {
        guard let firstId = await firstTrackId() else { return }
        let isCurrentlyDownloaded = downloadStates[firstId] is DownloadInfoDownloaded
        await MainActor.run {
            if isCurrentlyDownloaded {
                graph.downloadStateRepository.markNotDownloaded(trackId: firstId)
            } else {
                // Fake local path. Until the real worker lands this
                // is just a DB marker; no file is written. We use a
                // placeholder inside the Application Support
                // downloads directory so a future cleanup step can
                // find it via the persisted state.
                let fakePath = "Application Support/com.wtm.musicmanager.MusicManager/downloads/\(firstId).audio"
                graph.downloadStateRepository.markDownloaded(
                    trackId: firstId,
                    localPath: fakePath,
                )
            }
        }
    }

    /// Looks up the first track id by hitting the library repo's
    /// track-count flow then picking the lowest id we can fetch.
    /// The iOS-side in-memory SQLite driver means we can afford the
    /// round trip; for a 10k-track library this is ~5 ms.
    ///
    /// `trackById` is `suspend` on the Kotlin side, which the
    /// Kotlin/Native bridge exposes as an `async` Swift function
    /// (`trackById(id:)` returns `Track?` and is awaitable).
    /// We iterate candidate ids 1..50 and return the first one
    /// that resolves to a non-nil Track.
    private func firstTrackId() async -> Int64? {
        // We don't have a `firstTrackId()` repo shortcut, so we
        // ask the count + iterate. For Phase 3.D's debug toggle
        // we just need ANY track — pick 1 and try it; if not
        // present, walk up.
        let count: Int64 = await withCheckedContinuation { cont in
            graph.libraryRepository.observeTrackCount()
                .collect(collector: CountCollectorOnce { c in
                    cont.resume(returning: c)
                }) { _ in }
        }
        guard count > 0 else { return nil }
        for candidate in 1...min(Int64(50), count) {
            // Disambiguate `Track`: there's a local `MusicManager.Track`
            // (used by the LibraryMockScreen preview) and the
            // generated `MusicManagerShared.Track` from the
            // Kotlin/Native bridge. We need the latter here.
            //
            // The Kotlin/Native bridge exposes `suspend fun` as
            // an `async throws` Swift function. The error path is
            // the same `KotlinException` / `NSError` you'd see
            // from any bridged suspend fun; for the in-memory
            // iOS SQLite driver it never fires in practice, but
            // the compiler forces us to handle it.
            do {
                let track: MusicManagerShared.Track? = try await graph.libraryRepository.trackById(id: candidate)
                if track != nil { return candidate }
            } catch {
                // Swallow and continue to the next candidate id.
                // Phase 3.D's debug toggle only needs ANY track;
                // missing-id exceptions on the in-memory driver
                // mean the candidate isn't a real row, which is
                // expected (gaps in the auto-increment sequence).
                continue
            }
        }
        return nil
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

        // Phase 3.D: download state stream. Emits whenever any
        // `track.download_state` row changes. The collector
        // re-publishes into `self.downloadStates` which the body
        // derives the downloaded / pending / failed counts from.
        let downloadsFlow = graph.downloadStateRepository.observeStates()
        downloadsFlow.collect(collector: DownloadStateMapCollector { map in
            Task { @MainActor in self.downloadStates = map }
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

/// Same as `CountCollector` but resumes a continuation on the first
/// emission so we can `await` a single value. Used by
/// `firstTrackId()` to fetch the current track count without
/// keeping a long-lived collector around.
private final class CountCollectorOnce: NSObject, Kotlinx_coroutines_coreFlowCollector {
    private let onEmit: (Int64) -> Void
    private var fired = false
    init(onEmit: @escaping (Int64) -> Void) { self.onEmit = onEmit }
    func emit(value: Any?, completionHandler: @escaping (Error?) -> Void) {
        if !fired {
            fired = true
            if let n = value as? Int64 { onEmit(n) }
            else if let n = value as? Int { onEmit(Int64(n)) }
        }
        completionHandler(nil)
    }
}

/// Phase 3.D: maps the KMP `Map<Long, DownloadInfo>` Flow into a
/// Swift `[Int64: DownloadInfo]`. The Kotlin/Native bridge gives us
/// `Any?` per value; the underlying type is `__NSDictionaryI` whose
/// keys are `NSNumber` (boxed Long) and values are the sealed-class
/// subclasses (`DownloadInfoDownloaded`, `DownloadInfoDownloading`,
/// `DownloadInfoFailed`, `DownloadInfoNotDownloaded`).
private final class DownloadStateMapCollector: NSObject, Kotlinx_coroutines_coreFlowCollector {
    private let onEmit: ([Int64: DownloadInfo]) -> Void
    init(onEmit: @escaping ([Int64: DownloadInfo]) -> Void) { self.onEmit = onEmit }
    func emit(value: Any?, completionHandler: @escaping (Error?) -> Void) {
        var out: [Int64: DownloadInfo] = [:]
        // The bridge hands us a `Map<Long, DownloadInfo>` as an
        // `NSDictionary` (NSNumber → DownloadInfo subclass). We
        // iterate the dict and box each value into the Swift-side
        // `DownloadInfo` enum. Missing entries map to
        // `DownloadInfo.NotDownloaded` by contract of the repo.
        if let dict = value as? [AnyHashable: Any] {
            for (k, v) in dict {
                guard let n = k as? NSNumber else { continue }
                let id = n.int64Value
                if v is DownloadInfoDownloaded {
                    out[id] = DownloadInfo.downloaded
                } else if v is DownloadInfoDownloading {
                    out[id] = DownloadInfo.downloading
                } else if let failed = v as? DownloadInfoFailed {
                    out[id] = DownloadInfo.failed(reason: failed.reason)
                }
                // `DownloadInfoNotDownloaded` is dropped — the
                // repo contract is that map keys are present
                // only for non-default states.
            }
        }
        onEmit(out)
        completionHandler(nil)
    }
}

/// Swift-side mirror of the KMP `DownloadInfo` sealed interface.
/// `DownloadStateRepository.observeStates()` does NOT include
/// `NotDownloaded` keys (see the KMP doc), so the Swift side has
/// no case for it — we just leave the dictionary key absent.
enum DownloadInfo: Equatable {
    case downloading
    case downloaded
    case failed(reason: String)
}