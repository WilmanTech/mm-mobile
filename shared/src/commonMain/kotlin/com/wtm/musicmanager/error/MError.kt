package com.wtm.musicmanager.error

/**
 * Domain-level error model. The Ktor `ResponseException` and HttpStatusCodes
 * get translated into one of these in the repository layer so the UI never
 * imports io.ktor.* — that keeps the surface area thin and the tests
 * don't need to mock the Ktor exception machinery.
 */
sealed class MError(val message: String) {
    /** Server reachable but rejected the request (HTTP 4xx). */
    class ClientError(val statusCode: Int, msg: String) : MError(msg)

    /** Server reachable but failed (HTTP 5xx). */
    class ServerError(val statusCode: Int, msg: String) : MError(msg)

    /** Server unreachable, DNS failure, timeout, etc. */
    class Network(msg: String) : MError(msg)

    /** Auth missing or expired — UI should send user back to pairing. */
    class Unauthenticated(msg: String = "Pairing token missing or expired") : MError(msg)

    /** Pairing handshake in an unexpected state. */
    class PairingInvalid(msg: String) : MError(msg)
}

/**
 * Maps an HTTP status code to the right MError subtype. 4xx → ClientError
 * (unless 401/403 → Unauthenticated), 5xx → ServerError, anything else
 * (e.g. thrown exception before the response) → Network.
 */
fun mapHttpStatus(status: Int, defaultMessage: String): MError = when (status) {
    401, 403 -> MError.Unauthenticated()
    in 400..499 -> MError.ClientError(status, defaultMessage)
    in 500..599 -> MError.ServerError(status, defaultMessage)
    else -> MError.Network(defaultMessage)
}
