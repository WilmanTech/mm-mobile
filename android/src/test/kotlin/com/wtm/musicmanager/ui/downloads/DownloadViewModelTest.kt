package com.wtm.musicmanager.ui.downloads

import com.wtm.musicmanager.db.Track
import com.wtm.musicmanager.download.DownloadInfo
import com.wtm.musicmanager.download.DownloadTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = CoroutineScope(testDispatcher + SupervisorJob())

    @BeforeTest
    fun setup() { Dispatchers.setMain(testDispatcher) }

    @AfterTest
    fun teardown() { Dispatchers.resetMain() }

    @Test
    fun `empty trigger state projects to empty infos map`() = runTest(testDispatcher) {
        val fake = FakeDownloadTrigger()
        val vm = buildVm(fake)
        advanceUntilIdle()
        assertEquals(emptyMap(), vm.uiState.value.infos)
        assertTrue(vm.infoFor(99L) is DownloadInfo.NotDownloaded)
        assertEquals(false, vm.isDownloaded(99L))
    }

    @Test
    fun `infoFor returns the entry from the trigger map`() = runTest(testDispatcher) {
        val fake = FakeDownloadTrigger()
        fake.setInfo(7L, DownloadInfo.Downloading(progressPercent = 42))
        val vm = buildVm(fake)
        advanceUntilIdle()
        val info = vm.infoFor(7L)
        assertTrue(info is DownloadInfo.Downloading)
        assertEquals(42, (info as DownloadInfo.Downloading).progressPercent)
        assertEquals(false, vm.isDownloaded(7L))
    }

    @Test
    fun `Downloaded entry makes isDownloaded return true`() = runTest(testDispatcher) {
        val fake = FakeDownloadTrigger()
        fake.setInfo(3L, DownloadInfo.Downloaded)
        val vm = buildVm(fake)
        advanceUntilIdle()
        assertEquals(true, vm.isDownloaded(3L))
    }

    @Test
    fun `enqueue delegates to the trigger`() = runTest(testDispatcher) {
        val fake = FakeDownloadTrigger()
        val vm = buildVm(fake)
        val track = fakeTrack(id = 11L)
        vm.enqueue(track)
        advanceUntilIdle()
        assertEquals(1, fake.enqueued.size)
        assertEquals(11L, fake.enqueued.first().id)
    }

    @Test
    fun `delete delegates to the trigger`() = runTest(testDispatcher) {
        val fake = FakeDownloadTrigger()
        val vm = buildVm(fake)
        val track = fakeTrack(id = 12L)
        vm.delete(track)
        advanceUntilIdle()
        assertEquals(1, fake.deleted.size)
        assertEquals(12L, fake.deleted.first().id)
    }

    private fun buildVm(fake: FakeDownloadTrigger): DownloadViewModel {
        val vm = DownloadViewModel(downloadTrigger = fake)
        val job = testScope.launch { vm.uiState.collect { /* swallow */ } }
        return vm
    }
}

private class FakeDownloadTrigger : DownloadTrigger {
    private val _state = MutableStateFlow<Map<Long, DownloadInfo>>(emptyMap())
    override val state: StateFlow<Map<Long, DownloadInfo>> = _state.asStateFlow()

    val enqueued = mutableListOf<Track>()
    val deleted = mutableListOf<Track>()

    fun setInfo(trackId: Long, info: DownloadInfo) {
        _state.value = _state.value + (trackId to info)
    }

    override fun enqueue(track: Track) {
        enqueued += track
        // Mirror what the real trigger would do for the UI to react
        // without depending on the worker scheduling.
        setInfo(track.id, DownloadInfo.Downloading(progressPercent = 0))
    }

    override fun delete(track: Track) {
        deleted += track
        setInfo(track.id, DownloadInfo.NotDownloaded)
    }

    override fun clearAll() {
        _state.value = emptyMap()
    }
}

internal fun fakeTrack(
    id: Long,
    title: String = "Test Track",
    localPath: String? = null,
): Track = Track(
    id = id,
    title = title,
    album_id = 1L,
    album_title = "Test Album",
    artist_id = 1L,
    artist_name = "Test Artist",
    duration_ms = 180_000L,
    track_number = 1L,
    bitrate = 320L,
    codec = "mp3",
    stream_url = null,
    local_path = localPath,
    download_state = "NotDownloaded",
    synced_at = 1L,
)