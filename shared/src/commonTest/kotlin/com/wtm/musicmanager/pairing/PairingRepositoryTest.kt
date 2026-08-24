package com.wtm.musicmanager.pairing

import app.cash.turbine.test
import com.wtm.musicmanager.network.MusicManagerApi
import com.wtm.musicmanager.network.PairingConfirmRequest
import com.wtm.musicmanager.network.PairingConfirmResponse
import com.wtm.musicmanager.network.PairingStartRequest
import com.wtm.musicmanager.network.PairingStartResponse
import com.wtm.musicmanager.network.PairingStatusResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingRepositoryTest {

    private val fakeNow: Long = 1_700_000_000_000L

    private fun json() = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun mockClient(handler: MockRequestHandler): HttpClient {
        val engine = MockEngine(handler)
        return HttpClient(engine) {
            install(ContentNegotiation) {
                json(this@PairingRepositoryTest.json())
            }
            defaultRequest {
                headers.append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            }
        }
    }

    private fun startHandler(
        sessionId: String = "sess-1",
        token: String = "tok-xyz",
        code: String = "blue-fox-quick-zest",
        expiresIn: Int = 300,
    ): MockRequestHandler = { request ->
        if (request.url.encodedPath.endsWith("/api/pairing/start")) {
            respond(
                content = json().encodeToString(
                    PairingStartResponse.serializer(),
                    PairingStartResponse(sessionId, token, code, expiresIn),
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        } else {
            respond("not found", HttpStatusCode.NotFound)
        }
    }

    @Test
    fun `start transitions from Idle to Pending and persists token immediately`() = runTest {
        val authStorage = InMemoryTokenStore()
        val api = MusicManagerApi(mockClient(startHandler()), baseUrl = "http://test")
        val repo = PairingRepository(api, authStorage, now = { fakeNow })

        repo.state.test {
            assertEquals(PairingState.Idle, awaitItem())

            val state = repo.start()
            assertTrue(state is PairingState.Pending)
            val pending = state as PairingState.Pending
            assertEquals("sess-1", pending.sessionId)
            assertEquals("tok-xyz", pending.token)
            assertEquals("blue-fox-quick-zest", pending.code)
            assertEquals(fakeNow, pending.startedAt)
            assertEquals(fakeNow + 300_000L, pending.expiresAt)

            assertEquals(state, awaitItem())
        }
        // Token was persisted eagerly so a backend restart mid-pairing
        // doesn't lose it.
        assertEquals("tok-xyz", authStorage.load())
    }

    @Test
    fun `refreshStatus transitions to Paired when server confirms`() = runTest {
        val authStorage = InMemoryTokenStore()
        val handler: MockRequestHandler = { request ->
            when {
                request.url.encodedPath.endsWith("/api/pairing/start") ->
                    respond(
                        json().encodeToString(
                            PairingStartResponse.serializer(),
                            PairingStartResponse("sess-2", "tok-xyz", "river-stone", 300),
                        ),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                request.url.encodedPath.endsWith("/api/pairing/status") ->
                    respond(
                        json().encodeToString(
                            PairingStatusResponse.serializer(),
                            PairingStatusResponse(
                                exists = true,
                                sessionId = "sess-2",
                                confirmed = true,
                                deviceName = "Mac Studio",
                                confirmedAt = 1_700_000_005.0,
                            ),
                        ),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        }
        val api = MusicManagerApi(mockClient(handler), baseUrl = "http://test")
        val repo = PairingRepository(api, authStorage, now = { fakeNow })

        repo.start()
        val paired = repo.refreshStatus("sess-2")

        assertTrue(paired is PairingState.Paired)
        val p = paired as PairingState.Paired
        assertEquals("tok-xyz", p.token)
        assertEquals("Mac Studio", p.deviceName)
        assertEquals(1_700_000_005_000L, p.pairedAt)
        assertEquals("tok-xyz", authStorage.load())
    }

    @Test
    fun `refreshStatus stays in Pending when server is still pending`() = runTest {
        val authStorage = InMemoryTokenStore()
        val handler: MockRequestHandler = { request ->
            when {
                request.url.encodedPath.endsWith("/api/pairing/start") ->
                    respond(
                        json().encodeToString(
                            PairingStartResponse.serializer(),
                            PairingStartResponse("sess-3", "tok-3", "rock-stone", 300),
                        ),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                request.url.encodedPath.endsWith("/api/pairing/status") ->
                    respond(
                        json().encodeToString(
                            PairingStatusResponse.serializer(),
                            PairingStatusResponse(
                                exists = true,
                                sessionId = "sess-3",
                                confirmed = false,
                                expired = false,
                                revoked = false,
                            ),
                        ),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        }
        val api = MusicManagerApi(mockClient(handler), baseUrl = "http://test")
        val repo = PairingRepository(api, authStorage, now = { fakeNow })

        repo.start()
        val state = repo.refreshStatus("sess-3")
        assertTrue(state is PairingState.Pending)
        assertEquals("sess-3", (state as PairingState.Pending).sessionId)
    }

    @Test
    fun `refreshStatus transitions to Expired when server returns expired=true`() = runTest {
        val authStorage = InMemoryTokenStore()
        val handler: MockRequestHandler = { request ->
            when {
                request.url.encodedPath.endsWith("/api/pairing/start") ->
                    respond(
                        json().encodeToString(
                            PairingStartResponse.serializer(),
                            PairingStartResponse("sess-4", "tok-4", "old-code", 300),
                        ),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                request.url.encodedPath.endsWith("/api/pairing/status") ->
                    respond(
                        json().encodeToString(
                            PairingStatusResponse.serializer(),
                            PairingStatusResponse(
                                exists = true,
                                sessionId = "sess-4",
                                confirmed = false,
                                expired = true,
                                revoked = false,
                            ),
                        ),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        }
        val api = MusicManagerApi(mockClient(handler), baseUrl = "http://test")
        val repo = PairingRepository(api, authStorage, now = { fakeNow })

        repo.start()
        val state = repo.refreshStatus("sess-4")
        assertTrue(state is PairingState.Expired)
        assertEquals("sess-4", (state as PairingState.Expired).sessionId)
    }

    @Test
    fun `refreshStatus transitions to Expired when exists=false`() = runTest {
        val authStorage = InMemoryTokenStore()
        val handler: MockRequestHandler = { request ->
            when {
                request.url.encodedPath.endsWith("/api/pairing/start") ->
                    respond(
                        json().encodeToString(
                            PairingStartResponse.serializer(),
                            PairingStartResponse("sess-5", "tok-5", "ghost-code", 300),
                        ),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                request.url.encodedPath.endsWith("/api/pairing/status") ->
                    respond(
                        """{"exists":false}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        }
        val api = MusicManagerApi(mockClient(handler), baseUrl = "http://test")
        val repo = PairingRepository(api, authStorage, now = { fakeNow })

        repo.start()
        val state = repo.refreshStatus("sess-5")
        assertTrue(state is PairingState.Expired)
    }

    @Test
    fun `refreshStatus on Idle is a no-op`() = runTest {
        val authStorage = InMemoryTokenStore()
        val handler: MockRequestHandler = {
            respond("not found", HttpStatusCode.NotFound)
        }
        val api = MusicManagerApi(mockClient(handler), baseUrl = "http://test")
        val repo = PairingRepository(api, authStorage, now = { fakeNow })

        val state = repo.refreshStatus("nonexistent-session")
        assertEquals(PairingState.Idle, state)
    }

    @Test
    fun `unpair clears token and resets to Idle`() = runTest {
        val authStorage = InMemoryTokenStore()
        val api = MusicManagerApi(mockClient(startHandler()), baseUrl = "http://test")
        val repo = PairingRepository(api, authStorage, now = { fakeNow })

        repo.state.test {
            assertEquals(PairingState.Idle, awaitItem())

            repo.start()
            assertTrue(awaitItem() is PairingState.Pending)

            repo.unpair()
            assertEquals(PairingState.Idle, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
        assertNull(authStorage.load())
    }

    @Test
    fun `confirm success transitions to Paired using the confirm response token`() = runTest {
        val authStorage = InMemoryTokenStore()
        val handler: MockRequestHandler = { request ->
            when {
                request.url.encodedPath.endsWith("/api/pairing/confirm") ->
                    respond(
                        content = json().encodeToString(
                            PairingConfirmResponse.serializer(),
                            PairingConfirmResponse(
                                status = "confirmed",
                                token = "tok-confirm",
                                deviceName = "My iPhone",
                                pairedAt = 1_700_000_010.0,
                            ),
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                request.url.encodedPath.endsWith("/api/pairing/status") ->
                    respond(
                        content = json().encodeToString(
                            PairingStatusResponse.serializer(),
                            PairingStatusResponse(
                                exists = true,
                                sessionId = "sess-c",
                                confirmed = true,
                                deviceName = "My iPhone",
                                confirmedAt = 1_700_000_010.0,
                            ),
                        ),
                        status = HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        }
        val api = MusicManagerApi(mockClient(handler), baseUrl = "http://test")
        val repo = PairingRepository(api, authStorage, now = { fakeNow })

        val state = repo.confirm("sess-c", "my-code", "My iPhone", "mobile")
        assertTrue(state is PairingState.Paired)
        val p = state as PairingState.Paired
        assertEquals("tok-confirm", p.token)
        assertEquals("My iPhone", p.deviceName)
        assertEquals("tok-confirm", authStorage.load())
    }

    @Test
    fun `start accepts optional deviceType`() = runTest {
        val authStorage = InMemoryTokenStore()
        val handler: MockRequestHandler = { request ->
            if (request.url.encodedPath.endsWith("/api/pairing/start")) {
                respond(
                    json().encodeToString(
                        PairingStartResponse.serializer(),
                        PairingStartResponse("sess-x", "tok-x", "code", 300),
                    ),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            } else {
                respond("not found", HttpStatusCode.NotFound)
            }
        }
        val api = MusicManagerApi(mockClient(handler), baseUrl = "http://test")
        val repo = PairingRepository(api, authStorage, now = { fakeNow })

        // The request body shape is exercised by MusicManagerApiTest's
        // contract test (the actual JSON serializer is what produces it).
        // Here we just assert the call doesn't blow up when deviceType is
        // set, and that Pending carries the expected session/code/token.
        val state = repo.start(deviceType = "tv")
        assertTrue(state is PairingState.Pending)
        assertEquals("tok-x", (state as PairingState.Pending).token)
    }

    /**
     * Bridge crash guard: if /api/pairing/start throws (e.g. iOS Darwin
     * engine rejecting local-network requests with NSError -1009 "Local
     * network prohibited"), PairingRepository.start() must NOT propagate
     * the raw exception out of the suspend fun — the KMP bridge can't
     * convert it to NSError and would SIGABRT the app. Instead it must
     * transition to PairingState.Error so the Swift UI lands on the
     * error screen.
     */
    @Test
    fun `start converts thrown exception to Error state and no exception escapes`() = runTest {
        val authStorage = InMemoryTokenStore()
        val handler: MockRequestHandler = { _ ->
            throw RuntimeException("Local network prohibited (NSURLErrorDomain -1009)")
        }
        val api = MusicManagerApi(mockClient(handler), baseUrl = "http://test")
        val repo = PairingRepository(api, authStorage, now = { fakeNow })

        // Must NOT throw — must return an Error state instead.
        val state = repo.start()
        assertTrue(state is PairingState.Error, "expected Error state, got $state")
        assertEquals(-1, (state as PairingState.Error).httpStatus)
        // Token was never persisted because the request failed.
        assertNull(authStorage.load())
    }
}

/** In-memory TokenStore for tests. */
private class InMemoryTokenStore : TokenStore {
    var token: String? = null

    override fun load(): String? = token
    override fun save(token: String) {
        this.token = token
    }
    override fun clear() {
        token = null
    }
}
