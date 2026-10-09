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
    fun `startup automatically loads the bundled list without starting playback`() = runTest {
        val importedChannels = CompletableDeferred<List<Channel>>()
        val player = FakePlayerManager()
        val viewModel = TvIptvViewModel(BundledIptvRepository { importedChannels.await() }, player)
        runCurrent()
        assertTrue(viewModel.state.value.loading)
        assertNull(viewModel.state.value.playingChannel)
        assertTrue(player.commands.isEmpty())

        importedChannels.complete(channels)
        runCurrent()
        assertEquals(channels, viewModel.state.value.channels)
        assertFalse(viewModel.state.value.loading)
        assertFalse(viewModel.state.value.loadFailed)
        assertNull(viewModel.state.value.playingChannel)
    }

    @Test
    fun `selecting a channel opens and starts that live stream`() = runTest {
        val player = FakePlayerManager()
        val viewModel = TvIptvViewModel(BundledIptvRepository { channels }, player)
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
        val viewModel = TvIptvViewModel(BundledIptvRepository { entries }, player)
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
        val viewModel = TvIptvViewModel(BundledIptvRepository { channels }, player)
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
        val viewModel = TvIptvViewModel(BundledIptvRepository { channels }, player)
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
        val viewModel = TvIptvViewModel(BundledIptvRepository { channels.take(1) }, player)
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
        val viewModel = TvIptvViewModel(BundledIptvRepository { channels }, player)
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
        val viewModel = TvIptvViewModel(BundledIptvRepository { channels }, player)
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
        val viewModel = TvIptvViewModel(BundledIptvRepository { channels }, player)
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
        val repository = BundledIptvRepository {
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
        val viewModel = TvIptvViewModel(BundledIptvRepository { channels }, player)
        val store = ViewModelStore().apply { put("iptv", viewModel) }
        runCurrent()
        viewModel.play(channels[0])
        runCurrent()
        store.clear()

        assertEquals(1, player.releaseCount)
        assertFalse(player.isPlaying.value)
    }

    @Test
    fun `bundled playlist preserves 191 entries including 188 LAN streams`() {
        val lines = checkNotNull(javaClass.getResourceAsStream("/iptv.m3u"))
            .bufferedReader().use { it.readLines() }
        assertTrue(lines.first().startsWith("#EXTM3U"))
        val entries = lines.filter { it.startsWith("#EXTINF:") }
        val urls = lines.filter { it.startsWith("http://") || it.startsWith("https://") }
        assertEquals(191, entries.size)
        assertEquals(entries.size, urls.size)
        assertEquals(188, urls.count { it.startsWith("http://192.168.50.11:8888/rtp/") })
        assertTrue(entries.first().endsWith(",CCTV1"))
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
