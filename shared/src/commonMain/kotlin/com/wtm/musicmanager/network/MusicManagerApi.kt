package com.wtm.musicmanager.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType

class MusicManagerApi(
    private val client: HttpClient,
    private val baseUrl: String,
) {
    suspend fun fullSync(): FullSyncResponse =
        client.get("$baseUrl/api/v1/sync/full").body()

    suspend fun changesSince(since: Long): ChangesSyncResponse =
        client.get("$baseUrl/api/v1/sync/changes") {
            header("X-Since", since.toString())
        }.body()

    suspend fun startPairing(request: StartPairingRequest): StartPairingResponse =
        client.post("$baseUrl/api/pairing/start") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    suspend fun pairingStatus(sessionId: String): PairingStatusResponse =
        client.get("$baseUrl/api/pairing/status") {
            header("X-Session-Id", sessionId)
        }.body()

    suspend fun confirmPairing(request: ConfirmPairingRequest): HttpResponse =
        client.post("$baseUrl/api/pairing/confirm") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
}
