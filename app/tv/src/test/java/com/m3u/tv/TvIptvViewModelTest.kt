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
import com.m3u.data.repository.playlist.IptvQuality
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
    fun `Favorite is the default and uses the requested order in every quality mode`() = runTest {
        val titles = listOf(
            "CCTV1", "CCTV2", "CCTV13", "CCTV9", "CCTV10", "CCTV5", "CCTV5+", "CCTV16",
            "江苏卫视", "浙江卫视", "东方卫视", "北京卫视",
        )
        val entries = (titles.reversed() + "CCTV3").mapIndexed { index, title ->
            channels.first().copy(id = index + 10, title = title)
        }
        for (quality in IptvQuality.entries) {
            val repository = FakeBundledIptvRepository { entries }.apply { savedQuality = quality }
            val viewModel = TvIptvViewModel(repository, FakePlayerManager())
            runCurrent()
            assertEquals(TvIptvTab.Favorite, viewModel.state.value.selectedTab)
            assertEquals(titles, viewModel.state.value.channels.map(Channel::title))
            assertEquals(entries.map(Channel::title), viewModel.state.value.allChannels.map(Channel::title))
            viewModel.closePlayer()
        }
    }

    @Test
    fun `radio is excluded from every tab and saved radio cannot resume`() = runTest {
        val entries = channels + listOf(
            channels.first().copy(id = 3, title = "金陵之声", category = "广播"),
            channels.first().copy(id = 4, title = "江苏新闻广播", category = "其他"),
            channels.first().copy(id = 5, title = "FM", category = "Radio"),
        )
        for (quality in IptvQuality.entries) {
            val repository = FakeBundledIptvRepository { entries }.apply {
                savedQuality = quality
                savedChannelTitle = "金陵之声"
            }
            val viewModel = TvIptvViewModel(repository, FakePlayerManager())
            runCurrent()
            assertEquals("CCTV1", viewModel.state.value.playingChannel?.title)
            assertEquals(listOf("CCTV1", "CCTV2"), viewModel.state.value.allChannels.map(Channel::title))
            viewModel.closePlayer()
            viewModel.selectTab(TvIptvTab.All)
            assertEquals(listOf("CCTV1", "CCTV2"), viewModel.state.value.channels.map(Channel::title))
        }
    }

    @Test
    fun `tab selection cancels numeric input and maps numbering and switching to that tab`() = runTest {
        val entries = channels + listOf(
            channels.first().copy(id = 3, title = "CCTV3"),
            channels.first().copy(id = 13, title = "CCTV13"),
        )
        val player = FakePlayerManager()
        val viewModel = TvIptvViewModel(FakeBundledIptvRepository { entries }, player)
        runCurrent()
        viewModel.closePlayer()
        viewModel.enterChannelDigit(2)
        viewModel.selectTab(TvIptvTab.All)
        assertEquals("", viewModel.state.value.channelNumberInput)
        assertEquals(entries, viewModel.state.value.channels)
        viewModel.enterChannelDigit(3)
        viewModel.confirmChannelNumber()
        runCurrent()
        assertEquals("CCTV3", viewModel.state.value.playingChannel?.title)
        viewModel.closePlayer()
        viewModel.selectTab(TvIptvTab.Favorite)
        viewModel.enterChannelDigit(3)
        viewModel.confirmChannelNumber()
        runCurrent()
        assertEquals("CCTV13", viewModel.state.value.playingChannel?.title)
        viewModel.nextChannel()
        runCurrent()
        assertEquals("CCTV1", viewModel.state.value.playingChannel?.title)
        viewModel.previousChannel()
        runCurrent()
        assertEquals("CCTV13", viewModel.state.value.playingChannel?.title)
        viewModel.closePlayer()
        assertEquals(13, viewModel.state.value.focusedChannelId)
        assertEquals(TvIptvTab.Favorite, viewModel.state.value.selectedTab)
    }

    @Test
    fun `saved nonfavorite television resumes and switching enters the default tab`() = runTest {
        val entries = channels + channels.first().copy(id = 3, title = "CCTV3")
        for (next in listOf(true, false)) {
            val repository = FakeBundledIptvRepository { entries }.apply { savedChannelTitle = "CCTV3" }
            val viewModel = TvIptvViewModel(repository, FakePlayerManager())
            runCurrent()
            assertEquals("CCTV3", viewModel.state.value.playingChannel?.title)
            assertEquals(TvIptvTab.Favorite, viewModel.state.value.selectedTab)
            if (next) viewModel.nextChannel() else viewModel.previousChannel()
            runCurrent()
            assertEquals(if (next) "CCTV1" else "CCTV2", viewModel.state.value.playingChannel?.title)
            viewModel.closePlayer()
        }
    }

    @Test
    fun `quality changes preserve tab and focused channel while remapping ids`() = runTest {
        val entries = channels + channels.first().copy(id = 13, title = "CCTV13")
        val viewModel = TvIptvViewModel(FakeBundledIptvRepository { entries }, FakePlayerManager())
        runCurrent()
        viewModel.closePlayer()
        viewModel.focusChannel(entries.last())
        viewModel.setQuality(IptvQuality.UHD_LOW)
        runCurrent()
        assertEquals(TvIptvTab.Favorite, viewModel.state.value.selectedTab)
        assertEquals(listOf("CCTV1", "CCTV2", "CCTV13"), viewModel.state.value.channels.map(Channel::title))
        assertEquals(213, viewModel.state.value.focusedChannelId)
        viewModel.selectTab(TvIptvTab.All)
        viewModel.setQuality(IptvQuality.UHD_HIGH)
        runCurrent()
        assertEquals(TvIptvTab.All, viewModel.state.value.selectedTab)
        assertEquals(113, viewModel.state.value.focusedChannelId)
    }

    @Test
    fun `bundled assets provide exactly twelve favorites and 140 television channels per quality`() {
        val expected = listOf(
            "CCTV1", "CCTV2", "CCTV13", "CCTV9", "CCTV10", "CCTV5", "CCTV5+", "CCTV16",
            "江苏卫视", "浙江卫视", "东方卫视", "北京卫视",
        )
        for (quality in IptvQuality.entries) {
            val metadata = checkNotNull(javaClass.classLoader?.getResource(quality.assetName))
                .readText().lineSequence().filter { it.startsWith("#EXTINF:") }.toList()
            val television = metadata.filterNot { it.contains("group-title=\"广播\"") }
                .mapIndexed { index, line -> channels.first().copy(id = index + 1, title = line.substringAfterLast(',')) }
            assertEquals(140, television.size)
            assertEquals(expected, TvIptvTab.Favorite.channelsFrom(television).map(Channel::title))
        }
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
    fun `previous and next switch by playlist order rather than channel id`() = runTest {
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
        assertEquals(IptvQuality.HD, first.state.value.quality)
        assertEquals(listOf(IptvQuality.HD), repository.loadedModes)
        first.focusChannel(channels[1])
        first.setQuality(IptvQuality.UHD_HIGH)
        runCurrent()
        assertEquals(IptvQuality.UHD_HIGH, first.state.value.quality)
        assertEquals(IptvQuality.UHD_HIGH, repository.savedQuality)
        assertEquals(channels[1].id + 100, first.state.value.focusedChannelId)
        assertEquals(listOf(IptvQuality.HD, IptvQuality.UHD_HIGH), repository.loadedModes)
        assertTrue(player.commands.isEmpty())

        val restarted = TvIptvViewModel(repository, player)
        runCurrent()
        assertEquals(IptvQuality.UHD_HIGH, restarted.state.value.quality)
        assertEquals(channels[0].id + 100, restarted.state.value.channels.first().id)
        restarted.enterChannelDigit(2)
        restarted.confirmChannelNumber()
        runCurrent()
        assertEquals(channels[1].id + 100, restarted.state.value.playingChannel?.id)
        restarted.closePlayer()
        restarted.setQuality(IptvQuality.HD)
        runCurrent()
        assertEquals(IptvQuality.HD, repository.savedQuality)
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
            viewModel.setQuality(IptvQuality.UHD_HIGH)
            runCurrent()
            assertTrue(viewModel.state.value.loadFailed)
            assertFalse(viewModel.state.value.loading)
            assertEquals(IptvQuality.HD, viewModel.state.value.quality)
            assertEquals(IptvQuality.HD, repository.savedQuality)
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
        viewModel.setQuality(IptvQuality.UHD_HIGH)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals("", viewModel.state.value.channelNumberInput)
        assertTrue(player.commands.isEmpty())
        viewModel.play(viewModel.state.value.channels.first())
        runCurrent()
        viewModel.setQuality(IptvQuality.HD)
        runCurrent()
        assertEquals(IptvQuality.UHD_HIGH, viewModel.state.value.quality)
        assertEquals(listOf(IptvQuality.HD, IptvQuality.UHD_HIGH), repository.loadedModes)
    }

    @Test
    fun `startup restores the last channel by name using the saved quality source`() = runTest {
        val repository = FakeBundledIptvRepository { channels }.apply {
            savedQuality = IptvQuality.UHD_HIGH
            savedChannelTitle = "CCTV2"
        }
        val player = FakePlayerManager()
        val viewModel = TvIptvViewModel(repository, player)
        runCurrent()
        assertEquals(IptvQuality.UHD_HIGH, viewModel.state.value.quality)
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
    fun `returning to the same ViewModel autoplays the last played channel not the grid focus`() = runTest {
        val repository = FakeBundledIptvRepository { channels }
        val player = FakePlayerManager()
        val viewModel = TvIptvViewModel(repository, player)
        runCurrent()
        viewModel.play(channels[1])
        runCurrent()
        viewModel.closePlayer()
        viewModel.focusChannel(channels.first())
        viewModel.onAppStopped(changingConfigurations = false)
        val releases = player.releaseCount
        viewModel.onAppStarted()
        assertTrue(viewModel.state.value.restoringPlayback)
        runCurrent()
        assertEquals(channels[1], viewModel.state.value.playingChannel)
        assertTrue(player.isPlaying.value)
        assertFalse(viewModel.state.value.restoringPlayback)
        assertEquals<List<MediaCommand>>(listOf(1, 2, 2).map(MediaCommand::Common), player.commands)
        assertEquals(releases, player.releaseCount)
        viewModel.onAppStarted()
        runCurrent()
        assertEquals(3, player.commands.size)
    }

    @Test
    fun `backgrounding pauses resources and returning restores the saved quality source`() = runTest {
        val repository = FakeBundledIptvRepository { channels }.apply {
            savedChannelTitle = "CCTV2"
            savedQuality = IptvQuality.UHD_HIGH
        }
        val player = FakePlayerManager()
        val viewModel = TvIptvViewModel(repository, player)
        runCurrent()
        viewModel.pauseOrContinue(false)
        assertFalse(player.isPlaying.value)
        viewModel.onAppStopped(changingConfigurations = false)
        assertEquals(1, player.releaseCount)
        viewModel.onAppStarted()
        runCurrent()
        assertEquals(102, viewModel.state.value.playingChannel?.id)
        assertTrue(player.isPlaying.value)
        assertEquals<List<MediaCommand>>(listOf(102, 102).map(MediaCommand::Common), player.commands)
        assertEquals(listOf(IptvQuality.UHD_HIGH, IptvQuality.UHD_HIGH), repository.loadedModes)
    }

    @Test
    fun `configuration recreation preserves pause and the grid without replaying`() = runTest {
        val repository = FakeBundledIptvRepository { channels }
        val player = FakePlayerManager()
        val viewModel = TvIptvViewModel(repository, player)
        runCurrent()
        viewModel.pauseOrContinue(false)
        viewModel.onAppStopped(changingConfigurations = true)
        viewModel.onAppStarted()
        runCurrent()
        assertFalse(player.isPlaying.value)
        assertEquals(0, player.releaseCount)
        assertEquals(1, player.commands.size)
        viewModel.closePlayer()
        viewModel.onAppStopped(changingConfigurations = true)
        viewModel.onAppStarted()
        runCurrent()
        assertNull(viewModel.state.value.playingChannel)
        assertEquals(1, player.commands.size)
    }

    @Test
    fun `stopping during import cancels background startup and restarts it on foreground`() = runTest {
        val gate = CompletableDeferred<List<Channel>>()
        val repository = FakeBundledIptvRepository { gate.await() }
        val player = FakePlayerManager()
        val viewModel = TvIptvViewModel(repository, player)
        runCurrent()
        viewModel.onAppStopped(changingConfigurations = false)
        gate.complete(channels)
        runCurrent()
        assertTrue(player.commands.isEmpty())
        viewModel.onAppStarted()
        runCurrent()
        assertEquals(channels.first(), viewModel.state.value.playingChannel)
        assertTrue(player.isPlaying.value)
        assertEquals<List<MediaCommand>>(listOf(MediaCommand.Common(1)), player.commands)
    }

    @Test
    fun `warm restore read failure shows retry and retry still starts the saved channel`() = runTest {
        val repository = FakeBundledIptvRepository { channels }
        val player = FakePlayerManager()
        val viewModel = TvIptvViewModel(repository, player)
        runCurrent()
        viewModel.play(channels[1])
        runCurrent()
        repository.failReadingChannel = true
        viewModel.onAppStopped(changingConfigurations = false)
        viewModel.onAppStarted()
        runCurrent()
        assertTrue(viewModel.state.value.loadFailed)
        assertNull(viewModel.state.value.playingChannel)
        assertFalse(player.isPlaying.value)
        assertEquals(2, player.commands.size)
        repository.failReadingChannel = false
        viewModel.loadPlaylist()
        runCurrent()
        assertEquals(channels[1], viewModel.state.value.playingChannel)
        assertTrue(player.isPlaying.value)
    }

    @Test
    fun `quality playlists keep identical channel numbering and select known 1080p alternatives`() {
        fun playlist(name: String): List<String> =
            checkNotNull(javaClass.getResourceAsStream("/$name"))
                .bufferedReader().use { reader -> reader.readLines() }
        val hd = playlist("iptv.m3u")
        val uhd = playlist("iptv-4k.m3u")
        val low = playlist("iptv-4k-low.m3u")
        val metadata = { lines: List<String> -> lines.filter { it.startsWith("#EXTINF:") } }
        assertEquals(metadata(hd), metadata(uhd))
        assertEquals(metadata(hd), metadata(low))
        assertEquals(150, metadata(hd).size)
        fun url(lines: List<String>, title: String): String =
            lines[lines.indexOfFirst { it.endsWith(",$title") } + 1]
        assertEquals("http://192.168.50.11:8888/rtp/239.49.8.31:8000", url(hd, "CCTV16"))
        assertEquals("http://192.168.50.11:8888/rtp/239.49.0.109:8000", url(uhd, "CCTV16"))
        for (title in listOf("江苏卫视", "四川卫视")) {
            assertFalse(url(hd, title) == url(uhd, title))
        }
        for (title in listOf("CCTV1", "CCTV13", "CCTV4K")) {
            assertEquals(url(hd, title), url(uhd, title))
        }
        for (lines in listOf(hd, low)) {
            assertEquals("http://192.168.50.11:8888/rtp/239.49.1.63:8000", url(lines, "浙江卫视"))
        }
        assertEquals("http://192.168.50.11:8888/rtp/239.49.1.64:8000", url(uhd, "浙江卫视"))
        assertEquals("http://192.168.50.11:8888/rtp/239.49.1.61:8000", url(low, "江苏卫视"))
        assertEquals("http://192.168.50.11:8888/rtp/239.49.1.62:8000", url(uhd, "江苏卫视"))
        assertEquals("http://192.168.50.11:8888/rtp/239.49.8.19:9614", url(hd, "CCTV1"))
        assertEquals("http://192.168.50.11:8888/rtp/239.49.8.75:8000", url(hd, "CCTV5"))
        assertEquals("http://192.168.50.11:8888/rtp/239.49.8.109:8000", url(hd, "CCTV13"))
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

    @Test
    fun `all three qualities persist and restore actual channel selection on startup and warm return`() = runTest {
        for (quality in IptvQuality.entries) {
            val repository = FakeBundledIptvRepository { channels }
            val player = FakePlayerManager()
            val first = startOnGrid(repository, player)
            first.focusChannel(channels[1])
            first.setQuality(quality)
            runCurrent()
            assertEquals(quality, first.state.value.quality)
            assertEquals(quality, repository.savedQuality)
            val selected = first.state.value.channels[1]
            assertEquals(selected.id, first.state.value.focusedChannelId)
            assertNull(first.state.value.playingChannel)
            first.play(selected)
            runCurrent()
            val restarted = TvIptvViewModel(repository, player)
            runCurrent()
            assertEquals(quality, restarted.state.value.quality)
            assertEquals(selected, restarted.state.value.playingChannel)
            restarted.onAppStopped(changingConfigurations = false)
            restarted.onAppStarted()
            runCurrent()
            assertEquals(selected, restarted.state.value.playingChannel)
            assertTrue(player.isPlaying.value)
        }
    }

    @Test
    fun `quality cycle and persisted legacy preferences preserve upgrade behavior`() {
        assertEquals(IptvQuality.UHD_LOW, IptvQuality.HD.next())
        assertEquals(IptvQuality.UHD_HIGH, IptvQuality.UHD_LOW.next())
        assertEquals(IptvQuality.HD, IptvQuality.UHD_HIGH.next())
        assertEquals(IptvQuality.HD, IptvQuality.fromPreference(null, false))
        assertEquals(IptvQuality.UHD_HIGH, IptvQuality.fromPreference(null, true))
        for (quality in IptvQuality.entries) {
            assertEquals(quality, IptvQuality.fromPreference(quality.preferenceValue, false))
            assertEquals(quality, IptvQuality.fromPreference(quality.preferenceValue, true))
        }
    }

    @Test(expected = IllegalStateException::class)
    fun `unknown stored quality reports an error instead of silently resetting`() {
        IptvQuality.fromPreference("invalid", true)
    }

    @Test
    fun `low bitrate quality load and save errors leave the stored choice unchanged`() = runTest {
        for (failLoading in listOf(true, false)) {
            val repository = FakeBundledIptvRepository { channels }.apply {
                fail4kLoading = failLoading
                failSaving = !failLoading
            }
            val viewModel = startOnGrid(repository, FakePlayerManager())
            viewModel.setQuality(IptvQuality.UHD_LOW)
            runCurrent()
            assertTrue(viewModel.state.value.loadFailed)
            assertEquals(IptvQuality.HD, viewModel.state.value.quality)
            assertEquals(IptvQuality.HD, repository.savedQuality)
        }
    }

    private fun TestScope.startOnGrid(
        repository: BundledIptvRepository,
        player: FakePlayerManager,
    ): TvIptvViewModel {
        val viewModel = TvIptvViewModel(repository, player)
        runCurrent()
        viewModel.closePlayer()
        viewModel.selectTab(TvIptvTab.All)
        runCurrent()
        player.commands.clear()
        player.releaseCount = 0
        player.playbackCancelled = false
        return viewModel
    }

    private class FakeBundledIptvRepository(
        private val channels: suspend () -> List<Channel>,
    ) : BundledIptvRepository {
        var savedQuality = IptvQuality.HD
        var failSaving = false
        var fail4kLoading = false
        var savedChannelTitle: String? = null
        var failReadingChannel = false
        var failSavingChannel = false
        var saveChannelGate: CompletableDeferred<Unit>? = null
        val loadedModes = mutableListOf<IptvQuality>()

        override suspend fun load(quality: IptvQuality): List<Channel> {
            loadedModes += quality
            if (quality != IptvQuality.HD && fail4kLoading) throw IOException("4K playlist unavailable")
            return channels().map { channel ->
                when (quality) {
                    IptvQuality.HD -> channel
                    IptvQuality.UHD_LOW -> channel.copy(id = channel.id + 200, url = channel.url + "/4k-low")
                    IptvQuality.UHD_HIGH -> channel.copy(id = channel.id + 100, url = channel.url + "/4k")
                }
            }
        }

        override suspend fun getQuality(): IptvQuality = savedQuality

        override suspend fun setQuality(value: IptvQuality) {
            if (failSaving) throw IOException("Preference storage unavailable")
            savedQuality = value
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
