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

    private lazy var pairingRepository: PairingRepository = {
        makePairingRepository(host: host, port: port)
    }()

    private var stateObserverTask: Task<Void, Never>?

    init() {
        startObserving()
        Task { await restoreFromDisk() }
    }

    deinit {
        stateObserverTask?.cancel()
    }

    // MARK: - Public actions

    /// Update the backend target. Re-creates the pairing repo so the new host/port
    /// is honored on the next start() call.
    func updateBackend(host: String, port: String) {
        self.host = host
        self.port = port
        pairingRepository = makePairingRepository(host: host, port: port)
        startObserving()
    }

    /// Build a `PairingRepository` against the given host/port. The whole
    /// Ktor + AuthStorage wiring lives in Kotlin's `PairingEntry` so the
    /// Swift side stays oblivious to those types.
    private func makePairingRepository(host: String, port: String) -> PairingRepository {
        return PairingEntry.shared.make(host: host, port: port)
    }

    /// Start a pairing session. The backend returns the 4-word code immediately;
    /// we poll `/api/pairing/status` every 2s until the desktop confirms.
    func startPairing() async {
        do {
            let state: PairingState = try await withCheckedThrowingContinuation { cont in
                pairingRepository.start(deviceType: "ios") { state, error in
                    if let error = error {
                        cont.resume(throwing: error)
                    } else {
                        cont.resume(returning: state!)
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
                pairingRepository.confirm(
                    sessionId: sessionId,
                    code: code,
                    deviceName: hostname(),
                    deviceType: "ios"
                ) { state, error in
                    if let error = error {
                        cont.resume(throwing: error)
                    } else {
                        cont.resume(returning: state!)
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

    /// Handle an `mm://pair?session=...&token=...&code=...&host=...&port=...` URL.
    /// The desktop sends this after the user scans a QR code.
    func handleDeepLink(_ url: URL) {
        guard url.scheme == "mm" else { return }
        guard url.host == "pair" else { return }
        let comps = URLComponents(url: url, resolvingAgainstBaseURL: false)
        let items = comps?.queryItems ?? []
        let dict = Dictionary(uniqueKeysWithValues: items.map { ($0.name, $0.value ?? "") })

        if let token = dict["token"], !token.isEmpty {
            let deviceName = dict["device"].flatMap { $0.isEmpty ? nil : $0 }
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
                self.phase = Self.phase(from: state)
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
                pairingRepository.restore { state, error in
                    if let error = error {
                        cont.resume(throwing: error)
                    } else {
                        cont.resume(returning: state!)
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
                    pairingRepository.refreshStatus(sessionId: sessionId) { state, error in
                        if let error = error {
                            cont.resume(throwing: error)
                        } else {
                            cont.resume(returning: state!)
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
        lastError = "Pairing timed out waiting for confirmation"
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

#if canImport(UIKit)
import UIKit
#endif
