package com.m3u.tv

import android.graphics.Rect
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import com.m3u.data.database.model.Channel
import com.m3u.data.database.model.Playlist
import com.m3u.data.repository.playlist.BundledIptvRepository
import com.m3u.data.service.MediaCommand
import com.m3u.data.service.PlayerManager
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before

@OptIn(ExperimentalCoroutinesApi::class)
class TvIptvViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val channels = listOf(
        Channel("http://192.168.50.11:8888/rtp/239.49.0.207:8000", "CCTV", "CCTV1", playlistUrl = "iptv", id = 1),
        Channel("http://192.168.50.11:8888/rtp/239.49.8.50:9802", "CCTV", "CCTV2", playlistUrl = "iptv", id = 2),
    )

    @Before
    fun setMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `startup waits for import then automatically plays CCTV1 when no channel is saved`() = runTest {
        val importedChannels = CompletableDeferred<List<Channel>>()
        val player = FakePlayerManager()
        val viewModel = TvIptvViewModel(FakeBundledIptvRepository { importedChannels.await() }, player)
        runCurrent()
        assertTrue(viewModel.state.value.loading)
        assertNull(viewModel.state.value.playingChannel)
        assertTrue(player.commands.isEmpty())

        importedChannels.complete(channels)
        runCurrent()
        assertEquals(channels, viewModel.state.value.channels)
        assertFalse(viewModel.state.value.loading)
        assertFalse(viewModel.state.value.loadFailed)
        assertEquals(channels.first(), viewModel.state.value.playingChannel)
        assertEquals<List<MediaCommand>>(listOf(MediaCommand.Common(1)), player.commands)
    }

    @Test
    fun `selecting a channel opens and starts that live stream`() = runTest {
        val player = FakePlayerManager()
        val viewModel = startOnGrid(FakeBundledIptvRepository { channels }, player)
        runCurrent()
        viewModel.play(channels[1])
        runCurrent()

        assertEquals(channels[1], viewModel.state.value.playingChannel)
        assertEquals(channels[1].id, viewModel.state.value.focusedChannelId)
        assertEquals<List<MediaCommand>>(listOf(MediaCommand.Common(channels[1].id)), player.commands)
        assertFalse(player.appliedContinueWatching)
    }

    @Test
    fun `up and down switch by playlist order rather than channel id`() = runTest {
        val entries = listOf(channels[0].copy(id = 40), channels[1].copy(id = 7), channels[0].copy(id = 91))
        val player = FakePlayerManager()
        val viewModel = startOnGrid(FakeBundledIptvRepository { entries }, player)
        runCurrent()
        viewModel.play(entries[0])
        runCurrent()
        viewModel.nextChannel()
        runCurrent()
        assertEquals(entries[1], viewModel.state.value.playingChannel)
        viewModel.nextChannel()
        runCurrent()
        assertEquals(entries[2], viewModel.state.value.playingChannel)
        viewModel.previousChannel()
        runCurrent()
        assertEquals(entries[1], viewModel.state.value.playingChannel)
        assertEquals<List<MediaCommand>>(
            listOf(40, 7, 91, 7).map(MediaCommand::Common),
            player.commands,
        )
        assertFalse(player.appliedContinueWatching)
        viewModel.closePlayer()
        assertEquals(entries[1].id, viewModel.state.value.focusedChannelId)
    }

    @Test
    fun `channel switching wraps between the first and last entries`() = runTest {
        val player = FakePlayerManager()
        val viewModel = startOnGrid(FakeBundledIptvRepository { channels }, player)
        runCurrent()
        viewModel.play(channels.first())
        runCurrent()
        viewModel.previousChannel()
        runCurrent()
        assertEquals(channels.last(), viewModel.state.value.playingChannel)
        viewModel.nextChannel()
        runCurrent()
        assertEquals(channels.first(), viewModel.state.value.playingChannel)
        assertEquals<List<MediaCommand>>(
            listOf(1, 2, 1).map(MediaCommand::Common),
            player.commands,
        )
    }

    @Test
    fun `channel switching does not autoplay while the list is open`() = runTest {
        val player = FakePlayerManager()
        val viewModel = startOnGrid(FakeBundledIptvRepository { channels }, player)
        viewModel.previousChannel()
        viewModel.nextChannel()
        runCurrent()
        viewModel.previousChannel()
        viewModel.nextChannel()
        runCurrent()
        assertNull(viewModel.state.value.playingChannel)
        assertTrue(player.commands.isEmpty())
    }

    @Test
    fun `a single channel wraps safely to itself`() = runTest {
        val player = FakePlayerManager()
        val viewModel = startOnGrid(FakeBundledIptvRepository { channels.take(1) }, player)
        runCurrent()
        viewModel.play(channels.first())
        runCurrent()
        viewModel.previousChannel()
        runCurrent()
        viewModel.nextChannel()
        runCurrent()
        assertEquals(channels.first(), viewModel.state.value.playingChannel)
        assertEquals<List<MediaCommand>>(
            List(3) { MediaCommand.Common(channels.first().id) },
            player.commands,
        )
    }

    @Test
    fun `switching cancels pending playback and closing keeps the latest channel focused`() = runTest {
        val player = FakePlayerManager().apply { suspendPlayback = true }
        val viewModel = startOnGrid(FakeBundledIptvRepository { channels }, player)
        runCurrent()
        viewModel.play(channels.first())
        runCurrent()
        viewModel.nextChannel()
        runCurrent()
        assertTrue(player.playbackCancelled)
        assertEquals(channels.last(), viewModel.state.value.playingChannel)
        assertEquals<List<MediaCommand>>(
            listOf(1, 2).map(MediaCommand::Common),
            player.commands,
        )
        viewModel.previousChannel()
        viewModel.nextChannel()
        runCurrent()
        assertEquals(channels.last(), viewModel.state.value.playingChannel)
        viewModel.closePlayer()
        runCurrent()
        assertNull(viewModel.state.value.playingChannel)
        assertEquals(channels.last().id, viewModel.state.value.focusedChannelId)
        assertFalse(player.isPlaying.value)
        assertFalse(viewModel.state.value.playbackStartFailed)
    }

    @Test
    fun `closing playback releases it and returns to the same list and channel`() = runTest {
        val player = FakePlayerManager()
        val viewModel = startOnGrid(FakeBundledIptvRepository { channels }, player)
        runCurrent()
        viewModel.play(channels[1])
        runCurrent()
        viewModel.closePlayer()

        assertNull(viewModel.state.value.playingChannel)
        assertEquals(channels, viewModel.state.value.channels)
        assertEquals(channels[1].id, viewModel.state.value.focusedChannelId)
        assertEquals(1, player.releaseCount)
        assertFalse(player.isPlaying.value)
    }

    @Test
    fun `back cancels pending playback so it cannot restart behind the list`() = runTest {
        val player = FakePlayerManager().apply { suspendPlayback = true }
        val viewModel = startOnGrid(FakeBundledIptvRepository { channels }, player)
        runCurrent()
        viewModel.play(channels[0])
        runCurrent()
        viewModel.closePlayer()
        runCurrent()

        assertTrue(player.playbackCancelled)
        assertEquals(1, player.releaseCount)
        assertNull(viewModel.state.value.playingChannel)
        assertFalse(player.isPlaying.value)
        assertFalse(viewModel.state.value.playbackStartFailed)
    }

    @Test
    fun `failed loading is visible and retry loads the list`() = runTest {
        var attempts = 0
        val repository = FakeBundledIptvRepository {
            attempts += 1
            if (attempts == 1) throw IOException("Asset unavailable")
            channels
        }
        val viewModel = TvIptvViewModel(repository, FakePlayerManager())
        runCurrent()
        assertTrue(viewModel.state.value.loadFailed)
        assertFalse(viewModel.state.value.loading)

        viewModel.loadPlaylist()
        runCurrent()
        assertFalse(viewModel.state.value.loadFailed)
        assertEquals(channels, viewModel.state.value.channels)
        assertEquals(2, attempts)
    }

    @Test
    fun `destroying the view model stops playback`() = runTest {
        val player = FakePlayerManager()
        val viewModel = startOnGrid(FakeBundledIptvRepository { channels }, player)
        val store = ViewModelStore().apply { put("iptv", viewModel) }
        runCurrent()
        viewModel.play(channels[0])
        runCurrent()
        store.clear()

        assertEquals(1, player.releaseCount)
        assertFalse(player.isPlaying.value)
    }

    @Test
    fun `numeric selection waits 1500 milliseconds and uses list position not database id`() = runTest {
        val entries = (1..17).map { channels.first().copy(title = "CCTV$it", id = 100 + it) }
        val player = FakePlayerManager()
        val viewModel = startOnGrid(FakeBundledIptvRepository { entries }, player)
        runCurrent()
        viewModel.enterChannelDigit(1)
        advanceTimeBy(1_000)
        viewModel.enterChannelDigit(3)
        advanceTimeBy(1_499)
        runCurrent()
        assertEquals("13", viewModel.state.value.channelNumberInput)
        assertTrue(player.commands.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(entries[12], viewModel.state.value.playingChannel)
        assertEquals<List<MediaCommand>>(listOf(MediaCommand.Common(113)), player.commands)
        assertEquals("", viewModel.state.value.channelNumberInput)
    }

    @Test
    fun `OK confirms immediately and cancels the delayed selection`() = runTest {
        val player = FakePlayerManager()
        val viewModel = startOnGrid(FakeBundledIptvRepository { channels }, player)
        runCurrent()
        viewModel.enterChannelDigit(2)
        viewModel.confirmChannelNumber()
        runCurrent()
        assertEquals(channels[1], viewModel.state.value.playingChannel)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(1, player.commands.size)
    }

    @Test
    fun `cancel prevents a numeric jump without closing current playback`() = runTest {
        val player = FakePlayerManager()
        val viewModel = startOnGrid(FakeBundledIptvRepository { channels }, player)
        runCurrent()
        viewModel.play(channels[0])
        runCurrent()
        viewModel.enterChannelDigit(2)
        viewModel.cancelChannelNumber()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(channels[0], viewModel.state.value.playingChannel)
        assertEquals(1, player.commands.size)
        assertEquals(0, player.releaseCount)
        assertEquals("", viewModel.state.value.channelNumberInput)
    }

    @Test
    fun `invalid numbers show feedback without interrupting playback and allow retry`() = runTest {
        val player = FakePlayerManager()
        val viewModel = startOnGrid(FakeBundledIptvRepository { channels }, player)
        runCurrent()
        viewModel.play(channels[0])
        runCurrent()
        for (digit in listOf(0, 9)) {
            viewModel.enterChannelDigit(digit)
            advanceTimeBy(1_500)
            runCurrent()
            assertEquals(digit.toString(), viewModel.state.value.rejectedChannelNumber)
            assertEquals(channels[0], viewModel.state.value.playingChannel)
            assertEquals(1, player.commands.size)
            advanceTimeBy(3_000)
            runCurrent()
            assertNull(viewModel.state.value.rejectedChannelNumber)
        }
        viewModel.enterChannelDigit(2)
        viewModel.confirmChannelNumber()
        runCurrent()
        assertEquals(channels[1], viewModel.state.value.playingChannel)
        assertNull(viewModel.state.value.rejectedChannelNumber)
    }

    @Test
    fun `leading zeros are accepted and excess digits are rejected without overflow`() = runTest {
        val player = FakePlayerManager()
        val viewModel = startOnGrid(FakeBundledIptvRepository { channels }, player)
        runCurrent()
        viewModel.enterChannelDigit(0)
        viewModel.enterChannelDigit(2)
        viewModel.confirmChannelNumber()
        runCurrent()
        assertEquals(channels[1], viewModel.state.value.playingChannel)
        viewModel.enterChannelDigit(1)
        viewModel.enterChannelDigit(3)
        assertEquals("13", viewModel.state.value.rejectedChannelNumber)
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(1, player.commands.size)
        viewModel.cancelChannelNumber()
    }

    @Test
    fun `Dpad switching and leaving playback cancel pending numeric selection`() = runTest {
        val player = FakePlayerManager()
        val viewModel = startOnGrid(FakeBundledIptvRepository { channels }, player)
        runCurrent()
        viewModel.play(channels[0])
        runCurrent()
        viewModel.enterChannelDigit(1)
        viewModel.nextChannel()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(channels[1], viewModel.state.value.playingChannel)
        assertEquals(2, player.commands.size)
        viewModel.enterChannelDigit(1)
        viewModel.closePlayer()
        advanceTimeBy(2_000)
        runCurrent()
        assertNull(viewModel.state.value.playingChannel)
        assertEquals(2, player.commands.size)
    }

    @Test
    fun `quality defaults to 1080p and persisted 4k selection loads before playback`() = runTest {
        val repository = FakeBundledIptvRepository { channels }
        val player = FakePlayerManager()
        val first = startOnGrid(repository, player)
        runCurrent()
        assertFalse(first.state.value.prefer4k)
        assertEquals(listOf(false), repository.loadedModes)
        first.focusChannel(channels[1])
        first.setPrefer4k(true)
        runCurrent()
        assertTrue(first.state.value.prefer4k)
        assertTrue(repository.savedPrefer4k)
        assertEquals(channels[1].id + 100, first.state.value.focusedChannelId)
        assertEquals(listOf(false, true), repository.loadedModes)
        assertTrue(player.commands.isEmpty())

        val restarted = TvIptvViewModel(repository, player)
        runCurrent()
        assertTrue(restarted.state.value.prefer4k)
        assertEquals(channels[0].id + 100, restarted.state.value.channels.first().id)
        restarted.enterChannelDigit(2)
        restarted.confirmChannelNumber()
        runCurrent()
        assertEquals(channels[1].id + 100, restarted.state.value.playingChannel?.id)
        restarted.closePlayer()
        restarted.setPrefer4k(false)
        runCurrent()
        assertFalse(repository.savedPrefer4k)
        assertEquals(channels[1].id, restarted.state.value.focusedChannelId)
    }

    @Test
    fun `quality load and save failures are visible without reporting or remembering success`() = runTest {
        for (failLoading in listOf(true, false)) {
            val repository = FakeBundledIptvRepository { channels }.apply {
                fail4kLoading = failLoading
                failSaving = !failLoading
            }
            val viewModel = startOnGrid(repository, FakePlayerManager())
            runCurrent()
            viewModel.setPrefer4k(true)
            runCurrent()
            assertTrue(viewModel.state.value.loadFailed)
            assertFalse(viewModel.state.value.loading)
            assertFalse(viewModel.state.value.prefer4k)
            assertFalse(repository.savedPrefer4k)
            viewModel.loadPlaylist()
            runCurrent()
            assertFalse(viewModel.state.value.loadFailed)
            assertEquals(channels, viewModel.state.value.channels)
        }
    }

    @Test
    fun `quality change cancels pending numbers and cannot reimport while playing`() = runTest {
        val repository = FakeBundledIptvRepository { channels }
        val player = FakePlayerManager()
        val viewModel = startOnGrid(repository, player)
        runCurrent()
        viewModel.enterChannelDigit(2)
        viewModel.setPrefer4k(true)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals("", viewModel.state.value.channelNumberInput)
        assertTrue(player.commands.isEmpty())
        viewModel.play(viewModel.state.value.channels.first())
        runCurrent()
        viewModel.setPrefer4k(false)
        runCurrent()
        assertTrue(viewModel.state.value.prefer4k)
        assertEquals(listOf(false, true), repository.loadedModes)
    }

    @Test
    fun `startup restores the last channel by name using the saved quality source`() = runTest {
        val repository = FakeBundledIptvRepository { channels }.apply {
            savedPrefer4k = true
            savedChannelTitle = "CCTV2"
        }
        val player = FakePlayerManager()
        val viewModel = TvIptvViewModel(repository, player)
        runCurrent()
        assertTrue(viewModel.state.value.prefer4k)
        assertEquals("CCTV2", viewModel.state.value.playingChannel?.title)
        assertEquals(102, viewModel.state.value.playingChannel?.id)
        assertEquals(channels[1].url + "/4k", viewModel.state.value.playingChannel?.url)
        assertEquals<List<MediaCommand>>(listOf(MediaCommand.Common(102)), player.commands)
        assertFalse(player.appliedContinueWatching)
    }

    @Test
    fun `a removed saved channel falls back to the first channel and replaces the saved title`() = runTest {
        val repository = FakeBundledIptvRepository { channels }.apply {
            savedChannelTitle = "Removed channel"
        }
        val player = FakePlayerManager()
        val viewModel = TvIptvViewModel(repository, player)
        runCurrent()
        assertEquals(channels.first(), viewModel.state.value.playingChannel)
        assertEquals("CCTV1", repository.savedChannelTitle)
        assertEquals<List<MediaCommand>>(listOf(MediaCommand.Common(1)), player.commands)
    }

    @Test
    fun `closing and reloading the grid do not autoplay or replace the last played channel with focus`() = runTest {
        val repository = FakeBundledIptvRepository { channels }
        val player = FakePlayerManager()
        val viewModel = TvIptvViewModel(repository, player)
        runCurrent()
        viewModel.enterChannelDigit(2)
        viewModel.confirmChannelNumber()
        runCurrent()
        assertEquals("CCTV2", repository.savedChannelTitle)
        viewModel.closePlayer()
        viewModel.focusChannel(channels.first())
        viewModel.loadPlaylist()
        runCurrent()
        assertNull(viewModel.state.value.playingChannel)
        assertEquals("CCTV2", repository.savedChannelTitle)
        assertEquals(2, player.commands.size)
        val restarted = TvIptvViewModel(repository, FakePlayerManager())
        runCurrent()
        assertEquals(channels[1], restarted.state.value.playingChannel)
    }

    @Test
    fun `startup storage read failures are visible and retry still restores the channel`() = runTest {
        val repository = FakeBundledIptvRepository { channels }.apply {
            savedChannelTitle = "CCTV2"
            failReadingChannel = true
        }
        val player = FakePlayerManager()
        val viewModel = TvIptvViewModel(repository, player)
        runCurrent()
        assertTrue(viewModel.state.value.loadFailed)
        assertNull(viewModel.state.value.playingChannel)
        assertTrue(player.commands.isEmpty())
        repository.failReadingChannel = false
        viewModel.loadPlaylist()
        runCurrent()
        assertEquals(channels[1], viewModel.state.value.playingChannel)
        assertEquals<List<MediaCommand>>(listOf(MediaCommand.Common(2)), player.commands)
    }

    @Test
    fun `saving the last channel fails explicitly instead of silently losing the selection`() = runTest {
        val repository = FakeBundledIptvRepository { channels }.apply { failSavingChannel = true }
        val player = FakePlayerManager()
        val viewModel = TvIptvViewModel(repository, player)
        runCurrent()
        assertTrue(viewModel.state.value.playbackStartFailed)
        assertEquals(channels.first(), viewModel.state.value.playingChannel)
        assertTrue(player.commands.isEmpty())
        repository.failSavingChannel = false
        viewModel.play(channels.first())
        runCurrent()
        assertFalse(viewModel.state.value.playbackStartFailed)
        assertEquals("CCTV1", repository.savedChannelTitle)
        assertEquals<List<MediaCommand>>(listOf(MediaCommand.Common(1)), player.commands)
    }

    @Test
    fun `Back during startup persistence prevents delayed autoplay behind the grid`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeBundledIptvRepository { channels }.apply { saveChannelGate = gate }
        val player = FakePlayerManager()
        val viewModel = TvIptvViewModel(repository, player)
        runCurrent()
        assertEquals(channels.first(), viewModel.state.value.playingChannel)
        assertTrue(player.commands.isEmpty())
        viewModel.closePlayer()
        gate.complete(Unit)
        runCurrent()
        assertNull(viewModel.state.value.playingChannel)
        assertTrue(player.commands.isEmpty())
        assertNull(repository.savedChannelTitle)
    }

    @Test
    fun `quality playlists keep identical channel numbering and select known 1080p alternatives`() {
        fun playlist(name: String): List<String> =
            checkNotNull(javaClass.getResourceAsStream("/$name"))
                .bufferedReader().use { reader -> reader.readLines() }
        val hd = playlist("iptv.m3u")
        val uhd = playlist("iptv-4k.m3u")
        val metadata = { lines: List<String> -> lines.filter { it.startsWith("#EXTINF:") } }
        assertEquals(metadata(hd), metadata(uhd))
        assertEquals(150, metadata(hd).size)
        fun url(lines: List<String>, title: String): String =
            lines[lines.indexOfFirst { it.endsWith(",$title") } + 1]
        assertEquals("http://192.168.50.11:8888/rtp/239.49.8.31:8000", url(hd, "CCTV16"))
        assertEquals("http://192.168.50.11:8888/rtp/239.49.0.109:8000", url(uhd, "CCTV16"))
        for (title in listOf("江苏卫视", "四川卫视")) {
            assertFalse(url(hd, title) == url(uhd, title))
        }
        for (title in listOf("CCTV1", "CCTV13", "CCTV4K", "湖南卫视")) {
            assertEquals(url(hd, title), url(uhd, title))
        }
    }

    @Test
    fun `bundled playlist has 150 unique channels with CCTV numbers matching their positions`() {
        val lines = checkNotNull(javaClass.getResourceAsStream("/iptv.m3u"))
            .bufferedReader().use { it.readLines() }
        assertTrue(lines.first().startsWith("#EXTM3U"))
        val entries = lines.filter { it.startsWith("#EXTINF:") }
        val urls = lines.filter { it.startsWith("http://") || it.startsWith("https://") }
        assertEquals(150, entries.size)
        assertEquals(entries.size, urls.size)
        val titles = entries.map { it.substringAfterLast(',') }
        assertEquals(titles.size, titles.distinct().size)
        assertEquals(urls.size, urls.distinct().size)
        assertEquals((1..17).map { "CCTV$it" }, titles.take(17))
        assertEquals("CCTV5+", titles[17])
        val firstProvincial = entries.indexOfFirst { it.contains("group-title=\"卫视\"") }
        assertTrue(firstProvincial > 17)
        assertTrue(entries.take(firstProvincial).all { it.contains("group-title=\"央视\"") })
        assertTrue(entries[firstProvincial].endsWith(",浙江卫视"))
        assertEquals("江苏健康广播", titles.last())
    }

    private fun TestScope.startOnGrid(
        repository: BundledIptvRepository,
        player: FakePlayerManager,
    ): TvIptvViewModel {
        val viewModel = TvIptvViewModel(repository, player)
        runCurrent()
        viewModel.closePlayer()
        runCurrent()
        player.commands.clear()
        player.releaseCount = 0
        player.playbackCancelled = false
        return viewModel
    }

    private class FakeBundledIptvRepository(
        private val channels: suspend () -> List<Channel>,
    ) : BundledIptvRepository {
        var savedPrefer4k = false
        var failSaving = false
        var fail4kLoading = false
        var savedChannelTitle: String? = null
        var failReadingChannel = false
        var failSavingChannel = false
        var saveChannelGate: CompletableDeferred<Unit>? = null
        val loadedModes = mutableListOf<Boolean>()

        override suspend fun load(prefer4k: Boolean): List<Channel> {
            loadedModes += prefer4k
            if (prefer4k && fail4kLoading) throw IOException("4K playlist unavailable")
            return channels().map { channel ->
                if (prefer4k) channel.copy(id = channel.id + 100, url = channel.url + "/4k") else channel
            }
        }

        override suspend fun getPrefer4k(): Boolean = savedPrefer4k

        override suspend fun setPrefer4k(value: Boolean) {
            if (failSaving) throw IOException("Preference storage unavailable")
            savedPrefer4k = value
        }

        override suspend fun getLastChannelTitle(): String? {
            if (failReadingChannel) throw IOException("Last channel storage unavailable")
            return savedChannelTitle
        }

        override suspend fun setLastChannelTitle(value: String) {
            saveChannelGate?.await()
            if (failSavingChannel) throw IOException("Last channel could not be saved")
            savedChannelTitle = value
        }
    }

    private class FakePlayerManager : PlayerManager {
        override val player = MutableStateFlow<Player?>(null)
        override val isPlaying = MutableStateFlow(false)
        override val playbackState = MutableStateFlow(Player.STATE_IDLE)
        override val playbackException = MutableStateFlow<PlaybackException?>(null)
        val commands = mutableListOf<MediaCommand>()
        var appliedContinueWatching = true
        var releaseCount = 0
        var suspendPlayback = false
        var playbackCancelled = false

        override suspend fun play(command: MediaCommand, applyContinueWatching: Boolean) {
            commands += command
            appliedContinueWatching = applyContinueWatching
            if (suspendPlayback) {
                try {
                    awaitCancellation()
                } finally {
                    playbackCancelled = true
                }
            }
            isPlaying.value = true
        }

        override fun release() {
            releaseCount += 1
            isPlaying.value = false
            player.value = null
        }

        override fun pauseOrContinue(value: Boolean) {
            isPlaying.value = value
        }

        override val size: StateFlow<Rect> get() = error("Not used")
        override val channel: StateFlow<Channel?> get() = error("Not used")
        override val playlist: StateFlow<Playlist?> get() = error("Not used")
        override val tracksGroups: StateFlow<List<Tracks.Group>> get() = error("Not used")
        override val cacheSpace: Flow<Long> get() = error("Not used")
        override val cwPosition: SharedFlow<Long> get() = error("Not used")
        override fun chooseTrack(group: TrackGroup, index: Int): Unit = error("Not used")
        override fun clearTrack(type: Int): Unit = error("Not used")
        override suspend fun replay(): Unit = error("Not used")
        override fun clearCache(): Unit = error("Not used")
        override fun updateSpeed(race: Float): Unit = error("Not used")
        override suspend fun recordVideo(uri: Uri): Unit = error("Not used")
        override suspend fun onResetPlayback(channelUrl: String): Unit = error("Not used")
        override suspend fun getCwPosition(channelUrl: String): Long = error("Not used")
        override suspend fun reloadThumbnail(channelUrl: String): Uri? = error("Not used")
        override suspend fun syncThumbnail(channelUrl: String): Uri? = error("Not used")
    }
}
