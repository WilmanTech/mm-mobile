package com.wtm.musicmanager.pairing

import com.wtm.musicmanager.network.ConfirmPairingRequest
import com.wtm.musicmanager.network.MusicManagerApi
import com.wtm.musicmanager.network.PairingStatusResponse
import com.wtm.musicmanager.network.StartPairingRequest
import com.wtm.musicmanager.network.StartPairingResponse
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.client.HttpClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import app.cash.turbine.test
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

    /**
     * Minimal HttpClient backed by MockEngine. The test installs a per-test
     * `addHandler { request -> ... }` to control what each endpoint returns.
     */
    private fun mockClient(handler: io.ktor.client.engine.mock.MockRequestHandler): HttpClient {
        val engine = MockEngine(handler)
        return HttpClient(engine) {
            install(ContentNegotiation) { json(this@PairingRepositoryTest.json()) }
            defaultRequest {
                headers.append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            }
        }
    }

    private fun startHandler(
        sessionId: String = "sess-1",
        code: String = "blue-fox-quick-zest",
        expiresAt: Long? = fakeNow + 300_000L,
    ): io.ktor.client.engine.mock.MockRequestHandler = { request ->
        if (request.url.encodedPath.endsWith("/api/pairing/start")) {
            respond(
                content = json().encodeToString(
                    StartPairingResponse.serializer(),
                    StartPairingResponse(sessionId, code, expiresAt),
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        } else {
            respond("not found", HttpStatusCode.NotFound)
        }
    }

    @Test
    fun `start transitions from Idle to Pending`() = runTest {
        val authStorage = InMemoryTokenStore()
        val api = MusicManagerApi(mockClient(startHandler()), baseUrl = "http://test")
        val repo = PairingRepository(api, authStorage, now = { fakeNow })

        repo.state.test {
            assertEquals(PairingState.Idle, awaitItem())

            val state = repo.start()
            assertTrue(state is PairingState.Pending)
            assertEquals("sess-1", state.sessionId)
            assertEquals("blue-fox-quick-zest", state.code)
            assertEquals(fakeNow, state.startedAt)

            // State should also reflect the transition
            assertEquals(state, awaitItem())
        }
    }

    @Test
    fun `refreshStatus transitions to Paired and persists token`() = runTest {
        val authStorage = InMemoryTokenStore()
        val handler: io.ktor.client.engine.mock.MockRequestHandler = { request ->
            when {
                request.url.encodedPath.endsWith("/api/pairing/start") -> {
                    respond(
                        content = json().encodeToString(
                            StartPairingResponse.serializer(),
                            StartPairingResponse("sess-2", "river-stone", null),
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                request.url.encodedPath.endsWith("/api/pairing/status") -> {
                    respond(
                        content = json().encodeToString(
                            PairingStatusResponse.serializer(),
                            PairingStatusResponse(
                                status = "paired",
                                apiToken = "tok-xyz",
                                deviceName = "Mac Studio",
                                serverLabel = "Home Server",
                            ),
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        }
        val api = MusicManagerApi(mockClient(handler), baseUrl = "http://test")
        val repo = PairingRepository(api, authStorage, now = { fakeNow })

        repo.start()
        val paired = repo.refreshStatus("sess-2")

        assertTrue(paired is PairingState.Paired)
        assertEquals("tok-xyz", paired.token)
        assertEquals("Mac Studio", paired.deviceName)
        assertEquals("Home Server", paired.serverLabel)
        assertEquals(fakeNow, paired.pairedAt)

        // Token must be persisted to AuthStorage.
        assertEquals("tok-xyz", authStorage.load())
    }

    @Test
    fun `refreshStatus stays in Pending when server returns pending`() = runTest {
        val authStorage = InMemoryTokenStore()
        val handler: io.ktor.client.engine.mock.MockRequestHandler = { request ->
            when {
                request.url.encodedPath.endsWith("/api/pairing/start") -> {
                    respond(
                        content = json().encodeToString(
                            StartPairingResponse.serializer(),
                            StartPairingResponse("sess-3", "rock-stone", null),
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                request.url.encodedPath.endsWith("/api/pairing/status") -> {
                    respond(
                        content = json().encodeToString(
                            PairingStatusResponse.serializer(),
                            PairingStatusResponse(status = "pending"),
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        }
        val api = MusicManagerApi(mockClient(handler), baseUrl = "http://test")
        val repo = PairingRepository(api, authStorage, now = { fakeNow })

        repo.start()
        val state = repo.refreshStatus("sess-3")
        assertTrue(state is PairingState.Pending)
        assertEquals("sess-3", state.sessionId)
        assertNull(authStorage.load())
    }

    @Test
    fun `refreshStatus transitions to Expired`() = runTest {
        val authStorage = InMemoryTokenStore()
        val handler: io.ktor.client.engine.mock.MockRequestHandler = { request ->
            when {
                request.url.encodedPath.endsWith("/api/pairing/start") -> {
                    respond(
                        content = json().encodeToString(
                            StartPairingResponse.serializer(),
                            StartPairingResponse("sess-4", "old-code", null),
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                request.url.encodedPath.endsWith("/api/pairing/status") -> {
                    respond(
                        content = json().encodeToString(
                            PairingStatusResponse.serializer(),
                            PairingStatusResponse(status = "expired"),
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        }
        val api = MusicManagerApi(mockClient(handler), baseUrl = "http://test")
        val repo = PairingRepository(api, authStorage, now = { fakeNow })

        repo.start()
        val state = repo.refreshStatus("sess-4")
        assertTrue(state is PairingState.Expired)
        assertEquals("sess-4", state.sessionId)
    }

    @Test
    fun `refreshStatus on Idle is a no-op`() = runTest {
        val authStorage = InMemoryTokenStore()
        val handler: io.ktor.client.engine.mock.MockRequestHandler = {
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

            // First go to Pending so unpair() is a real state change.
            repo.start()
            assertTrue(awaitItem() is PairingState.Pending)

            repo.unpair()
            assertEquals(PairingState.Idle, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
        assertNull(authStorage.load())
    }

    @Test
    fun `confirm success transitions to Paired`() = runTest {
        val authStorage = InMemoryTokenStore()
        val handler: io.ktor.client.engine.mock.MockRequestHandler = { request ->
            when {
                request.url.encodedPath.endsWith("/api/pairing/confirm") -> {
                    respond("", HttpStatusCode.OK)
                }
                request.url.encodedPath.endsWith("/api/pairing/status") -> {
                    respond(
                        content = json().encodeToString(
                            PairingStatusResponse.serializer(),
                            PairingStatusResponse(
                                status = "paired",
                                apiToken = "tok-confirm",
                                deviceName = "My iPhone",
                                serverLabel = "Workstation",
                            ),
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        }
        val api = MusicManagerApi(mockClient(handler), baseUrl = "http://test")
        val repo = PairingRepository(api, authStorage, now = { fakeNow })

        val state = repo.confirm("sess-c", "my-code", "My iPhone")
        assertTrue(state is PairingState.Paired)
        assertEquals("tok-confirm", state.token)
        assertEquals("tok-confirm", authStorage.load())
    }
}

/** In-memory TokenStore for tests. */
private class InMemoryTokenStore : TokenStore {
    var token: String? = null
    var label: String? = null

    override fun load(): String? = token
    override fun save(token: String, serverLabel: String?) {
        this.token = token
        this.label = serverLabel
    }
    override fun clear() {
        token = null
        label = null
    }
}
