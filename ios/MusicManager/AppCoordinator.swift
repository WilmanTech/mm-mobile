import Foundation
import Combine
import MusicManagerShared

/// Top-level state holder for the iOS app. Wires together the KMP `PairingRepository`
/// (defined in `shared/`) with the SwiftUI views, and translates mm:// deep links
/// into pairing-token acceptance.
///
/// Why an `ObservableObject` instead of just reading `PairingRepository.state`
/// directly from the views? SwiftUI's `Body` needs `@Published` — Kotlin's
/// `StateFlow` doesn't bridge into SwiftUI's reactive primitives directly,
/// and we want to own the KMP repository's lifecycle anyway.
///
/// **Kotlin/Native bridge notes** (verified 2026-08-11 against the auto-generated
/// `MusicManagerShared.h` headers):
///   - `suspend fun foo()` becomes `fooWithCompletionHandler:` — `(Result?, NSError?) -> Void`
///   - `Flow.collect { ... }` becomes `collect(collector:completionHandler:)`;
///     the `FlowCollector` protocol has no Swift initialiser, so we wrap it in
///     an `NSObject` subclass (see `PairingStateCollector` below).
///   - `PairingState` sealed subclasses become separate Swift classes (e.g.
///     `PairingStatePaired`) that conform to the `PairingState` protocol.
///     There is no Swift enum-case bridging for sealed interfaces.
@MainActor
final class AppCoordinator: ObservableObject {

    /// Swift-side mirror of the Kotlin `PairingState` sealed interface.
    enum Phase: Equatable {
        case idle
        case pending(sessionId: String, code: String, expiresAt: Date)
        case paired(deviceName: String, pairedAt: Date)
        case error(message: String)
    }

    @Published var phase: Phase = .idle
    @Published var host: String = "127.0.0.1"
    @Published var port: String = "8765"
    @Published var lastError: String?
    @Published var showPreview: Bool = false

    /// Library graph wired by Phase 4.A.4 — populated the moment the app
    /// transitions into `.paired` and reset on `unpair()`. Swift views
    /// observe this `Optional` to decide whether to render the real
    /// `LibraryScreen` (graph != nil) or the post-pairing placeholder
    /// (graph == nil while `syncFull` is in flight).
    @Published private(set) var libraryGraph: LibraryEntry.Graph?

    /// Persistent bearer-token storage, built once and shared by both the
    /// pairing layer and the library layer. Constructing it through the
    /// Kotlin `TokenStore.from(storage:)` factory ensures the bearer set
    /// by `pairingRepository.acceptDeepLink(token:)` is the exact same
    /// token the library's `MusicManagerApi` reads on its authenticated
    /// requests — without this sharing, the first `/api/v1/sync/full`
    /// after pairing would 401.
    ///
    /// `DefaultAuthStorage_iosKt.defaultAuthStorage()` is the iOS top-level
    /// `actual fun defaultAuthStorage()` from `DefaultAuthStorage.ios.kt`.
    /// Kotlin/Native exposes top-level `expect`/`actual` functions as a
    /// Swift class named `<FileName>_<Platform>Kt` with the original name
    /// as a static method.
    ///
    /// Both the `AuthStorage` (the persistent backing) and the
    /// `TokenStore` (the read/write facade the Kotlin code uses) are
    /// constructed once and shared. `PairingEntry.make(...)` builds its
    /// own `TokenStore` internally from the same backing store via the
    /// Kotlin `PairingEntry` factory — so pairing writes go through the
    /// same store the library API reads from.
    private let authStorage: AuthStorage = DefaultAuthStorage_iosKt.defaultAuthStorage()

    private lazy var pairingRepository: PairingRepository = {
        makePairingRepository(host: host, port: port)
    }()

    private var stateObserverTask: Task<Void, Never>?
    private var syncTask: Task<Void, Never>?

    init() {
        startObserving()
        Task { await restoreFromDisk() }

        #if DEBUG
        // Visual review mode: when MM_VISUAL_REVIEW=1 is set (typically via
        // `xcrun simctl launch booted ... --env MM_VISUAL_REVIEW=1`), the
        // app auto-opens the LibraryMockScreen sheet so designers / reviewers
        // can land directly on the library preview without going through
        // pairing. This is a DEBUG-only path and never ships in Release.
        if ProcessInfo.processInfo.environment["MM_VISUAL_REVIEW"] == "1" {
            // Defer so the root view has time to mount the sheet on the
            // first frame, otherwise SwiftUI drops the binding.
            DispatchQueue.main.async { [weak self] in
                self?.showPreview = true
            }
        }

        // Test deep-link bypass — `simctl openurl` to a custom scheme triggers
        // iOS's "¿Abrir en MusicManager?" consent prompt the first time per
        // session, which blocks automated verification. Launching the app with
        // `xcrun simctl launch booted bundle -MM_TEST_TOKEN ***` skips the URL
        // round-trip and writes the bearer directly into the same `AuthStorage`
        // the real `acceptDeepLink` path uses.
        //
        // We use a single-dash prefix (not `--`) because `simctl launch` treats
        // double-dash arguments as its own flags and silently drops them before
        // the bundle's argv is constructed. Single-dash `-MM_TEST_TOKEN=***`
        // passes through to CommandLine.arguments verbatim.
        //
        // DEBUG-only and compile-time removed from Release builds.
        let args = CommandLine.arguments
        NSLog("MM_DEBUG_INIT argv.count=%d argv=%@", args.count, args.joined(separator: " | "))
        let tokenIdx = args.firstIndex(of: "-MM_TEST_TOKEN")
        NSLog("MM_DEBUG_INIT tokenIdx=%@", String(describing: tokenIdx))
        if let tokenIdx = tokenIdx,
           tokenIdx + 1 < args.count {
            // Optional overrides; fall back to current self.host / self.port.
            if let hostIdx = args.firstIndex(of: "-MM_TEST_HOST"),
               hostIdx + 1 < args.count {
                self.host = args[hostIdx + 1]
            }
            if let portIdx = args.firstIndex(of: "-MM_TEST_PORT"),
               portIdx + 1 < args.count {
                self.port = args[portIdx + 1]
            }
            pairingRepository = makePairingRepository(host: host, port: port)
            NSLog("MM_DEBUG_INIT about to call acceptDeepLink with token=%@", args[tokenIdx + 1])
            // Defer startObserving until acceptDeepLink completes so the
            // .paired emission that triggers rebuildLibraryGraph() doesn't
            // race the token write into AuthStorage (which gave us 401 on
            // /api/v1/sync/full in the 2026-08-12 smoke test). Kotlin
            // acceptDeepLink is sync (returns PairingState directly,
            // not a suspend fun), so once the call returns the token is
            // already persisted and the first .paired emission is safe
            // to act on.
            let pairedState = pairingRepository.acceptDeepLink(
                token: args[tokenIdx + 1],
                deviceName: "Test (launch-arg)"
            )
            NSLog("MM_DEBUG_INIT acceptDeepLink returned: %@", String(describing: pairedState))
            UserDefaults.standard.set(args[tokenIdx + 1], forKey: "direct_token_write")
            NSLog("MM_DEBUG_INIT direct_token_write done")
            // ORTOPEDIC bypass: the Kotlin/Native StateFlow.collect bridge
            // with completionHandler is async-by-construction — the
            // .paired emission often does not reach the SwiftUI Published
            // var before the view first renders, leaving the user stuck on
            // PairingScreen even though /sync/full returned 200. Force-set
            // phase = .paired synchronously so the view renders the Library
            // tab immediately; rebuildLibraryGraph() will still be called by
            // startObserving's first emission when the bridge catches up.
            self.phase = .paired(deviceName: "Test (launch-arg)", pairedAt: Date())
            NSLog("MM_DEBUG_INIT phase forced to .paired")
            rebuildLibraryGraph()
            NSLog("MM_DEBUG_INIT rebuildLibraryGraph called (orthopedic)")
            startObserving()
        }
        #endif
    }

    deinit {
        stateObserverTask?.cancel()
        syncTask?.cancel()
    }

    // MARK: - Public actions

    /// Update the backend target. Re-creates the pairing repo so the new host/port
    /// is honored on the next start() call. Also tears down the library
    /// graph (it would be pointing at the old backend) and re-creates it
    /// if we're still paired.
    func updateBackend(host: String, port: String) {
        self.host = host
        self.port = port
        pairingRepository = makePairingRepository(host: host, port: port)
        startObserving()
        if case .paired = phase {
            rebuildLibraryGraph()
        }
    }

    /// Build a `PairingRepository` against the given host/port. The whole
    /// Ktor + AuthStorage wiring lives in Kotlin's `PairingEntry` so the
    /// Swift side stays oblivious to those types.
    private func makePairingRepository(host: String, port: String) -> PairingRepository {
        return PairingEntry.shared.make(host: host, port: port)
    }

    /// Construct a fresh `LibraryEntry.Graph` against the current host/port
    /// and trigger `syncFull()` to repopulate the in-memory SQLite cache.
    /// The iOS `NativeSqliteDriver` is in-memory only (Pitfall #35 in the
    /// KMP bootstrap skill), so the cache is empty on every launch — the
    /// sync is what makes `observeTracks()` and `observeArtists()` emit
    /// anything other than an empty list.
    private func rebuildLibraryGraph() {
        syncTask?.cancel()
        libraryGraph = LibraryEntry.shared.make(host: host, port: port, tokenStore: authStorage)
        guard let graph = libraryGraph else { return }
        syncTask = Task { [weak self] in
            let result: SyncState? = await withCheckedContinuation { cont in
                graph.syncCoordinator.syncFull { state, error in
                    cont.resume(returning: state)
                }
            }
            await MainActor.run {
                self?.lastError = Self.errorMessage(for: result)
            }
        }
    }

    private static func errorMessage(for state: SyncState?) -> String? {
        guard let state else { return "Library sync returned no state" }
        if let failed = state as? SyncStateFailed {
            return "Library sync failed: \(failed.reason)"
        }
        return nil
    }

    /// Start a pairing session. The backend returns the 4-word code immediately;
    /// we poll `/api/pairing/status` every 2s until the desktop confirms.
    ///
    /// **Bridge crash guard**: Kotlin/Native sometimes invokes the
    /// `(state, error) -> Void` callback with `state == nil` AND
    /// `error == nil` when the sealed-class conversion to Swift fails
    /// (see verify mm-mobile audit 2026-08-12; reproduced against
    /// MusicManager backend at 192.168.1.201:8765). The previous
    /// `cont.resume(returning: state!)` force-unwrap crashed the app
    /// on tap. We now fall back to a synthetic `.error(...)` state so
    /// the user sees the failure screen instead of a SIGABRT.
    ///
    /// The continuation is also guarded against being resumed twice
    /// (Kotlin/Native can fire the callback once with the value, then
    /// once with an "operation cancelled" error). The first resume
    /// wins; the second is silently dropped.
    func startPairing() async {
        do {
            let state: PairingState = try await withCheckedThrowingContinuation { cont in
                let box = ContinuationBox<PairingState>(continuation: cont)
                pairingRepository.start(deviceType: "ios") { state, error in
                    if box.tryResume() == false { return }
                    if let error = error {
                        cont.resume(throwing: error)
                    } else if let state = state {
                        cont.resume(returning: state)
                    } else {
                        cont.resume(throwing: PairingStartError.emptyResponse)
                    }
                }
            }
            phase = Self.phase(from: state)
            await pollUntilConfirmed()
        } catch {
            lastError = "Start failed: \(error.localizedDescription)"
            phase = .error(message: lastError ?? "unknown")
        }
    }

    /// User typed the 4-word code on the desktop. We push it to /api/pairing/confirm.
    func confirmWithCode(_ code: String) async {
        guard case let .pending(sessionId, _, _) = phase else {
            lastError = "No pending session to confirm"
            return
        }
        do {
            let state: PairingState = try await withCheckedThrowingContinuation { cont in
                let box = ContinuationBox<PairingState>(continuation: cont)
                pairingRepository.confirm(
                    sessionId: sessionId,
                    code: code,
                    deviceName: hostname(),
                    deviceType: "ios"
                ) { state, error in
                    if box.tryResume() == false { return }
                    if let error = error {
                        cont.resume(throwing: error)
                    } else if let state = state {
                        cont.resume(returning: state)
                    } else {
                        cont.resume(throwing: PairingStartError.emptyResponse)
                    }
                }
            }
            phase = Self.phase(from: state)
        } catch {
            lastError = "Confirm failed: \(error.localizedDescription)"
            phase = .error(message: lastError ?? "unknown")
        }
    }

    func unpair() {
        pairingRepository.unpair()
        phase = .idle
    }

    #if DEBUG
    /// DEBUG-only bypass for visual / smoke verification: paste a bearer
    /// obtained from `/api/pairing/start` (returns `{session_id, token,
    /// code, expires_in}`) and the app transitions into the `.paired`
    /// state without going through the QR / `mm://` round-trip.
    ///
    /// The token is written into the same `AuthStorage` the real deep-link
    /// path uses, so `libraryGraph` is rebuilt on the next `state`
    /// emission and `syncFull()` runs against the live backend.
    ///
    /// Debug-only; compile-time removed from Release.
    func acceptTestToken(_ token: String) {
        pairingRepository.acceptDeepLink(
            token: token,
            deviceName: "Test (PairingScreen bypass)"
        )
    }
    #endif

    /// Handle an `mm://pair?session=...&token=...&code=...&host=...&port=...` URL.
    /// The desktop sends this after the user scans a QR code.
    ///
    /// Also recognises two preview URLs used during visual development
    /// (Phase 4.A.2 — the brand palette + glass material aren't
    /// exercised by the real pairing flow because the LibraryRepository
    /// hasn't landed yet on iOS):
    ///   - `mm://preview-library` — sets phase to a synthetic `.paired`
    ///     and opens `LibraryMockScreen` via the `showPreview` flag.
    func handleDeepLink(_ url: URL) {
        guard url.scheme == "mm" else { return }

        if url.host == "preview-library" {
            showPreview = true
            return
        }

        guard url.host == "pair" else { return }
        let comps = URLComponents(url: url, resolvingAgainstBaseURL: false)
        let items = comps?.queryItems ?? []
        let dict = Dictionary(uniqueKeysWithValues: items.map { ($0.name, ($0.value ?? "").trimmingCharacters(in: .whitespacesAndNewlines)) })

        if let token = dict["token"], !token.isEmpty {
            // URLComponents decoding can leave non-printable / encoded
            // artifacts at the boundaries of query values (e.g. trailing
            // `%20` from a hand-written URL or a ` ` slipped in by
            // a clipboard paste). Trim defensively so a stray space
            // doesn't silently corrupt the persisted bearer and 401
            // on the first /api/v1/sync/* request.
            // Verify mm-mobile audit 2026-08-12.
            let deviceName = dict["device"]?
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .nilIfEmpty()
                ?? "Paired via QR"
            pairingRepository.acceptDeepLink(token: token, deviceName: deviceName)
        }
    }

    // MARK: - Private helpers

    /// `PairingRepository.state` is a Kotlin `StateFlow<PairingState>`. The
    /// KMP/Native bridge exposes it as `id<Kotlinx_coroutines_coreStateFlow>`,
    /// and `collect(collector:completionHandler:)` is the only way to subscribe.
    ///
    /// We build an `NSObject` subclass that conforms to the
    /// `Kotlinx_coroutines_coreFlowCollector` protocol (which has a single
    /// required method `emit(value:completionHandler:)`) and forward each
    /// emission to the MainActor.
    private func startObserving() {
        stateObserverTask?.cancel()
        let repo = pairingRepository

        let collector = PairingStateCollector { [weak self] state in
            Task { @MainActor in
                guard let self else { return }
                let previousPhase = self.phase
                let newPhase = Self.phase(from: state)
                self.phase = newPhase

                // Side-effect on the paired/unpaired transition:
                //   entering .paired → build the LibraryEntry graph and
                //     trigger syncFull() (in-memory DB needs repopulation).
                //   leaving .paired → tear down the graph so the next
                //     pair starts from a clean slate.
                if case .paired = newPhase, !Self.isPaired(previousPhase) {
                    self.rebuildLibraryGraph()
                } else if Self.isPaired(previousPhase), !Self.isPaired(newPhase) {
                    self.libraryGraph = nil
                }
            }
        }

        stateObserverTask = Task {
            await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
                repo.state.collect(collector: collector) { error in
                    cont.resume()
                }
            }
        }
    }

    private func restoreFromDisk() async {
        do {
            let state: PairingState = try await withCheckedThrowingContinuation { cont in
                let box = ContinuationBox<PairingState>(continuation: cont)
                pairingRepository.restore { state, error in
                    if box.tryResume() == false { return }
                    if let error = error {
                        cont.resume(throwing: error)
                    } else if let state = state {
                        cont.resume(returning: state)
                    } else {
                        cont.resume(throwing: PairingStartError.emptyResponse)
                    }
                }
            }
            phase = Self.phase(from: state)
        } catch {
            // restore() failures are non-fatal — leave phase as Idle and let
            // the user try to pair again from the UI.
            lastError = "Restore failed: \(error.localizedDescription)"
        }
    }

    private func pollUntilConfirmed() async {
        guard case let .pending(sessionId, _, _) = phase else { return }
        for _ in 0..<30 {
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            guard !Task.isCancelled else { return }
            do {
                let state: PairingState = try await withCheckedThrowingContinuation { cont in
                    let box = ContinuationBox<PairingState>(continuation: cont)
                    pairingRepository.refreshStatus(sessionId: sessionId) { state, error in
                        if box.tryResume() == false { return }
                        if let error = error {
                            cont.resume(throwing: error)
                        } else if let state = state {
                            cont.resume(returning: state)
                        } else {
                            cont.resume(throwing: PairingStartError.emptyResponse)
                        }
                    }
                }
                if Self.isTerminal(state) {
                    phase = Self.phase(from: state)
                    return
                }
            } catch {
                // Transient polling failure — keep polling until the budget runs out.
            }
        }
        // 60s budget exhausted without a terminal state. Previously we only
        // surfaced this as lastError text while leaving phase = .pending,
        // which made the UI sit on "Waiting for desktop…" indefinitely and
        // required a relaunch to recover. Now we transition to a terminal
        // error phase so the user sees the failed pairing screen + retry
        // path automatically.
        // Verify mm-mobile audit 2026-08-12.
        lastError = "Pairing timed out waiting for confirmation"
        phase = .error(message: lastError ?? "Pairing timed out")
    }

    /// Kotlin/Native exposes each sealed-subclass as a separate Swift class
    /// that conforms to the `PairingState` protocol. There is no enum-case
    /// bridging — we use `is`-tests to discriminate at runtime.
    private static func isTerminal(_ state: PairingState) -> Bool {
        return state is PairingStatePaired
            || state is PairingStateExpired
            || state is PairingStateRevoked
            || state is PairingStateError
    }

    /// True when the Swift-side `Phase` is the connected state — used by
    /// the observer to detect the entering/leaving paired transitions
    /// that drive the library graph lifecycle.
    private static func isPaired(_ phase: Phase) -> Bool {
        if case .paired = phase { return true }
        return false
    }

    /// Map the KMP `PairingState` to our Swift `Phase` enum. See note in
    /// `isTerminal` above — Kotlin sealed subclasses arrive as separate
    /// Swift classes, not as enum cases.
    private static func phase(from state: PairingState) -> Phase {
        if state is PairingStateIdle {
            return .idle
        }
        if let pending = state as? PairingStatePending {
            return .pending(
                sessionId: pending.sessionId,
                code: pending.code,
                expiresAt: Date(timeIntervalSince1970: TimeInterval(pending.expiresAt) / 1000)
            )
        }
        if let paired = state as? PairingStatePaired {
            return .paired(
                deviceName: paired.deviceName,
                pairedAt: Date(timeIntervalSince1970: TimeInterval(paired.pairedAt) / 1000)
            )
        }
        if let expired = state as? PairingStateExpired {
            return .error(message: "Pairing session expired (\(expired.sessionId))")
        }
        if let revoked = state as? PairingStateRevoked {
            return .error(message: "Pairing revoked (\(revoked.sessionId))")
        }
        if let errorState = state as? PairingStateError {
            return .error(message: "HTTP \(errorState.httpStatus)")
        }
        return .idle
    }

    private func hostname() -> String {
        #if canImport(UIKit)
        return UIDevice.current.name
        #else
        return "iOS Device"
        #endif
    }
}

// MARK: - Bridge crash guards

/// Swift-side `CheckedContinuation` wrapper that makes the resume idempotent.
/// Kotlin/Native's `(Result?, NSError?) -> Void` bridge can fire the callback
/// twice in some edge cases (e.g. once with the value, once with a
/// cancellation error). Resuming a continuation twice is a fatal `precondition`
/// crash in Swift. The first resume wins; the second is silently dropped.
///
/// See verify mm-mobile audit 2026-08-12 (pairing crash on device).
private final class ContinuationBox<T> {
    private var continuation: CheckedContinuation<T, Error>?
    private let lock = NSLock()

    init(continuation: CheckedContinuation<T, Error>) {
        self.continuation = continuation
    }

    /// Returns `true` if this is the first resume attempt; `false` if a
    /// previous callback already consumed the continuation. The caller MUST
    /// NOT call `continuation.resume(...)` again when this returns `false`.
    func tryResume() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if continuation == nil { return false }
        continuation = nil
        return true
    }
}

/// Error thrown when the Kotlin callback fires with neither a state nor an
/// error. Usually means the sealed-class conversion to Swift failed inside
/// the bridge — see `startPairing()` for the full incident.
private enum PairingStartError: LocalizedError {
    case emptyResponse

    var errorDescription: String? {
        switch self {
        case .emptyResponse:
            return "Pairing backend returned an empty response"
        }
    }
}

// MARK: - FlowCollector adapter

/// `NSObject` subclass that conforms to the Kotlin/Native-generated
/// `Kotlinx_coroutines_coreFlowCollector` protocol so we can subscribe to
/// `StateFlow.collect(collector:completionHandler:)` from Swift.
///
/// The protocol has one required method:
///   `func emit(value: Any?, completionHandler: (NSError?) -> Void)`
///
/// We forward each value to a Swift closure on the calling thread — the
/// caller is responsible for dispatching to MainActor if needed (we do that
/// inside `AppCoordinator.startObserving`).
private final class PairingStateCollector: NSObject, Kotlinx_coroutines_coreFlowCollector {

    private let onEmit: (PairingState) -> Void

    init(onEmit: @escaping (PairingState) -> Void) {
        self.onEmit = onEmit
    }

    func emit(value: Any?, completionHandler: @escaping (Error?) -> Void) {
        if let state = value as? PairingState {
            onEmit(state)
        }
        completionHandler(nil)
    }
}

/// Returns nil when the string is empty / whitespace-only after trimming.
/// Used by `handleDeepLink` to coerce the optional `device` query param
/// into the same "no device name → fallback to default" semantics the
/// Kotlin repository applies.
extension String {
    func nilIfEmpty() -> String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}

#if canImport(UIKit)
import UIKit
#endif
