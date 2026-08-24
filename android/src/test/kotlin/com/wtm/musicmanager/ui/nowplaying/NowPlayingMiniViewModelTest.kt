package com.wtm.musicmanager.ui.nowplaying

import com.wtm.musicmanager.db.Track
import com.wtm.musicmanager.player.PlayerState
import com.wtm.musicmanager.player.PlayerTrigger
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
class NowPlayingMiniViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = CoroutineScope(testDispatcher + SupervisorJob())

    @BeforeTest
    fun setup() { Dispatchers.setMain(testDispatcher) }

    @AfterTest
    fun teardown() { Dispatchers.resetMain() }

    @Test
    fun `Idle state projects to Hidden`() = runTest(testDispatcher) {
        val vm = buildVm(initialPlayer = PlayerState.Idle)
        advanceUntilIdle()
        assertEquals(NowPlayingMiniUi.Hidden, vm.state.value)
    }

    @Test
    fun `Playing state projects to Active with isPlaying=true`() = runTest(testDispatcher) {
        val vm = buildVm(
            initialPlayer = PlayerState.Playing(
                track = fakeTrack(id = 1, title = "Bohemian Rhapsody", artistName = "Queen"),
                positionMs = 30_000L,
                durationMs = 354_000L,
            )
        )
        advanceUntilIdle()
        val ui = vm.state.value
        assertTrue(ui is NowPlayingMiniUi.Active)
        val active = ui as NowPlayingMiniUi.Active
        assertEquals("Bohemian Rhapsody", active.title)
        assertEquals("Queen", active.artist)
        assertEquals(true, active.isPlaying)
        assertEquals(30_000L, active.positionMs)
        assertEquals(354_000L, active.durationMs)
    }

    @Test
    fun `Paused state projects to Active with isPlaying=false`() = runTest(testDispatcher) {
        val vm = buildVm(
            initialPlayer = PlayerState.Paused(
                track = fakeTrack(id = 2, title = "Stairway to Heaven", artistName = "Led Zeppelin"),
                positionMs = 120_000L,
                durationMs = 480_000L,
            )
        )
        advanceUntilIdle()
        val ui = vm.state.value as NowPlayingMiniUi.Active
        assertEquals(false, ui.isPlaying)
        assertEquals("Stairway to Heaven", ui.title)
    }

    @Test
    fun `Loading state projects to Loading with track title`() = runTest(testDispatcher) {
        val vm = buildVm(
            initialPlayer = PlayerState.Loading(
                track = fakeTrack(id = 3, title = "Black Dog", artistName = "Led Zeppelin")
            )
        )
        advanceUntilIdle()
        val ui = vm.state.value
        assertTrue(ui is NowPlayingMiniUi.Loading)
        assertEquals("Black Dog", (ui as NowPlayingMiniUi.Loading).title)
    }

    @Test
    fun `Error state projects to Error with reason`() = runTest(testDispatcher) {
        val vm = buildVm(
            initialPlayer = PlayerState.Error(reason = "Network timeout")
        )
        advanceUntilIdle()
        val ui = vm.state.value
        assertTrue(ui is NowPlayingMiniUi.Error)
        assertEquals("Network timeout", (ui as NowPlayingMiniUi.Error).message)
    }

    @Test
    fun `playPause pass-through to PlayerTrigger`() = runTest(testDispatcher) {
        val fake = FakePlayerTrigger(initial = PlayerState.Idle)
        val vm = NowPlayingMiniViewModel(playerTrigger = fake)
        // skip the collector pattern — we only need stop() pass-through
        vm.playPause()
        advanceUntilIdle()
        assertEquals(1, fake.playPauseCalls)
    }

    @Test
    fun `stop pass-through to PlayerTrigger`() = runTest(testDispatcher) {
        val fake = FakePlayerTrigger(initial = PlayerState.Idle)
        val vm = NowPlayingMiniViewModel(playerTrigger = fake)
        vm.stop()
        advanceUntilIdle()
        assertEquals(1, fake.stopCalls)
    }

    @Test
    fun `play launches coroutine that calls PlayerTrigger play with the track`() = runTest(testDispatcher) {
        val fake = FakePlayerTrigger(initial = PlayerState.Idle)
        val vm = NowPlayingMiniViewModel(playerTrigger = fake)
        val track = fakeTrack(id = 99, title = "X")
        vm.play(track)
        advanceUntilIdle()
        assertEquals(1, fake.playCalls.size)
        assertEquals(99, fake.playCalls.first().id)
    }

    /**
     * Build the VM with [FakePlayerTrigger] and start a collector on
     * the testScope so the upstream combine() chain in NowPlayingMiniViewModel.state
     * (PlayerTrigger -> toUi) keeps flowing.
     */
    private fun buildVm(initialPlayer: PlayerState): NowPlayingMiniViewModel {
        val fake = FakePlayerTrigger(initial = initialPlayer)
        val vm = NowPlayingMiniViewModel(playerTrigger = fake)
        testScope.launch { vm.state.collect { /* swallow */ } }
        return vm
    }
}

private class FakePlayerTrigger(initial: PlayerState = PlayerState.Idle) : PlayerTrigger {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    var playPauseCalls = 0
        private set
    var stopCalls = 0
        private set
    val playCalls = mutableListOf<Track>()

    override suspend fun play(track: Track) {
        playCalls += track
        _state.value = PlayerState.Playing(track, positionMs = 0L, durationMs = 1000L)
    }

    override fun playPause() { playPauseCalls++ }
    override fun seek(positionMs: Long) {}
    override fun stop() {
        stopCalls++
        _state.value = PlayerState.Idle
    }
}

internal fun fakeTrack(
    id: Long,
    title: String,
    artistId: Long = 1L,
    artistName: String = "Test",
    albumId: Long = 1L,
): Track = Track(
    id = id,
    title = title,
    album_id = albumId,
    album_title = "Test Album",
    artist_id = artistId,
    artist_name = artistName,
    duration_ms = 180_000L,
    track_number = 1L,
    bitrate = 320L,
    codec = "mp3",
    stream_url = null,
    local_path = null,
    download_state = "NotDownloaded",
    synced_at = 1L,
)
