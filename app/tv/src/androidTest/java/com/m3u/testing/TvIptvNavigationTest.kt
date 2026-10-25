package com.m3u.testing

import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.m3u.i18n.R.plurals
import com.m3u.i18n.R.string
import com.m3u.tv.MainActivity
import com.m3u.tv.TvPlayerScreen
import com.m3u.tv.TvIptvViewModel
import com.m3u.tv.TvIptvTab
import com.m3u.data.database.model.Channel
import com.m3u.data.repository.playlist.IptvQuality
import com.m3u.core.foundation.architecture.preferences.settings
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TvIptvNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val timeout = 15_000L

    @Test
    fun backFromScrolledChannelReturnsToSelectedTabThenExits() {
        for (tab in TvIptvTab.entries) {
            launchOnGrid(tab = tab).use { scenario ->
                val row = if (tab == TvIptvTab.Favorite) 2 else 9
                repeat(row) { assertTrue(device.pressDPadDown()) }
                val index = 1 + row * 4
                var channel: Channel? = null
                scenario.onActivity { activity ->
                    channel = ViewModelProvider(activity)[TvIptvViewModel::class.java].state.value.channels[index - 1]
                }
                val selectedChannel = checkNotNull(channel)
                waitForChannelFocus(index, selectedChannel.title, selectedChannel.category)
                device.executeShellCommand("input keyevent 4")
                val label = context.getString(
                    if (tab == TvIptvTab.Favorite) string.tv_iptv_tab_favorite else string.tv_iptv_tab_all
                )
                waitForFocus(label)
                assertTrue(device.hasObject(By.desc(label).selected(true)))
                assertEquals(Lifecycle.State.RESUMED, scenario.state)
                scenario.onActivity { activity ->
                    val state = ViewModelProvider(activity)[TvIptvViewModel::class.java].state.value
                    assertEquals(tab, state.selectedTab)
                    assertEquals(selectedChannel.id, state.focusedChannelId)
                }
                assertTrue(device.pressBack())
                assertTrue(device.wait(Until.gone(By.pkg(context.packageName)), timeout))
            }
        }
    }

    @Test
    fun backDuringNumericInputCancelsBeforeReturningToTopMenu() {
        launchOnGrid(tab = TvIptvTab.Favorite).use { scenario ->
            device.executeShellCommand("input keyevent 9")
            device.executeShellCommand("input keyevent 4")
            waitForChannelFocus(index = 1)
            scenario.onActivity { activity ->
                val state = ViewModelProvider(activity)[TvIptvViewModel::class.java].state.value
                assertEquals("", state.channelNumberInput)
                assertEquals(null, state.playingChannel)
            }
            device.executeShellCommand("input keyevent 4")
            val label = context.getString(string.tv_iptv_tab_favorite)
            waitForFocus(label)
        }
    }

    @Test
    fun backFromQualityMenuExitsWithoutReturningToTab() {
        launchOnGrid(tab = TvIptvTab.Favorite).use { scenario ->
            assertTrue(device.pressDPadUp())
            assertTrue(device.pressDPadUp())
            var quality = IptvQuality.HD
            scenario.onActivity { activity ->
                quality = ViewModelProvider(activity)[TvIptvViewModel::class.java].state.value.quality
            }
            val label = context.getString(
                when (quality) {
                    IptvQuality.HD -> string.tv_iptv_quality_1080p
                    IptvQuality.UHD_LOW -> string.tv_iptv_quality_4k_low
                    IptvQuality.UHD_HIGH -> string.tv_iptv_quality_4k_high
                }
            )
            waitForFocus(label)
            assertTrue(device.pressBack())
            assertTrue(device.wait(Until.gone(By.pkg(context.packageName)), timeout))
        }
    }

    @Test
    fun favoriteIsDefaultAndTabsAreReachableByDpad() {
        launchOnGrid(tab = TvIptvTab.Favorite).use { scenario ->
            val favoriteLabel = context.getString(string.tv_iptv_tab_favorite)
            val allLabel = context.getString(string.tv_iptv_tab_all)
            val count = context.resources.getQuantityString(plurals.tv_channel_count, 19, 19)
            assertTrue(device.hasObject(By.text(count)))
            assertTrue(device.hasObject(By.desc(favoriteLabel).selected(true)))
            assertTrue(device.pressDPadUp())
            waitForFocus(favoriteLabel)
            assertTrue(device.pressDPadRight())
            waitForFocus(allLabel)
            assertTrue(device.pressDPadCenter())
            waitForChannelFocus(index = 1)
            assertTrue(device.hasObject(By.desc(allLabel).selected(true)))
            val allCount = context.resources.getQuantityString(plurals.tv_channel_count, 140, 140)
            assertTrue(device.hasObject(By.text(allCount)))
            scenario.onActivity { activity ->
                val state = ViewModelProvider(activity)[TvIptvViewModel::class.java].state.value
                assertEquals(140, state.channels.size)
                assertFalse(state.channels.any { it.category == "广播" })
            }
            assertTrue(device.pressDPadUp())
            waitForFocus(allLabel)
            assertTrue(device.pressDPadLeft())
            assertTrue(device.pressDPadCenter())
            waitForChannelFocus(index = 1)
            assertTrue(device.hasObject(By.text(count)))
            assertTrue(device.pressDPadRight())
            assertTrue(device.pressDPadRight())
            waitForChannelFocus(index = 3, title = "CCTV3")
            scenario.recreate()
            waitForChannelFocus(index = 3, title = "CCTV3")
        }
    }

    @Test
    fun favoriteNumericAndPlayerSwitchingFollowFavoriteOrder() {
        launchOnGrid(tab = TvIptvTab.Favorite).use {
            val closeLabel = context.getString(string.tv_action_close_player)
            device.executeShellCommand("input keyevent 10 23")
            assertTrue(device.wait(Until.hasObject(By.desc(closeLabel)), timeout))
            assertTrue(device.pressBack())
            waitForChannelFocus(index = 3, title = "CCTV3")
            assertTrue(device.pressDPadCenter())
            assertTrue(device.wait(Until.hasObject(By.desc(closeLabel)), timeout))
            assertTrue(device.pressDPadUp())
            assertTrue(device.pressBack())
            waitForChannelFocus(index = 4, title = "CCTV4")
        }
    }

    @Test
    fun returningToGridShowsAllChannelsWithoutDuplicatingThemOnRelaunch() {
        repeat(2) {
            launchOnGrid().use {
                waitForChannelFocus(index = 1)
                val count = context.resources.getQuantityString(plurals.tv_channel_count, 140, 140)
                assertTrue(device.hasObject(By.text(count)))
            }
        }
    }

    @Test
    fun returningToGridShowsOnlyChannelsWithInitialDpadFocus() {
        launchOnGrid().use {
            waitForChannelFocus(index = 1)
            val count = context.resources.getQuantityString(plurals.tv_channel_count, 140, 140)
            assertTrue(device.hasObject(By.text(count)))
            listOf(
                string.tv_home_title,
                string.tv_library_title,
                string.tv_favorites_title,
                string.tv_settings_title,
            ).forEach { title ->
                assertFalse(device.hasObject(By.desc(context.getString(title))))
            }
            assertFalse(device.hasObject(By.desc(context.getString(string.tv_action_close_player))))
        }
    }

    @Test
    fun playingThenBackOrCloseReturnsFocusToTheSelectedChannel() {
        launchOnGrid().use {
            waitForChannelFocus(index = 1)
            assertTrue(device.pressDPadRight())
            waitForChannelFocus(index = 2)
            val closeLabel = context.getString(string.tv_action_close_player)

            assertTrue(device.pressDPadCenter())
            assertTrue(device.wait(Until.hasObject(By.desc(closeLabel)), timeout))
            assertTrue(device.pressBack())
            waitForChannelFocus(index = 2)
            assertFalse(device.hasObject(By.desc(closeLabel)))

            assertTrue(device.pressDPadCenter())
            assertTrue(device.wait(Until.hasObject(By.desc(closeLabel)), timeout))
            device.findObject(By.desc(closeLabel)).click()
            waitForChannelFocus(index = 2)
            assertFalse(device.hasObject(By.desc(closeLabel)))
        }
    }

    @Test
    fun gridHasFourColumnsWithHorizontalAndVerticalDpadNavigation() {
        launchOnGrid().use {
            waitForChannelFocus(index = 1)
            val cards = (1..4).map { index ->
                val title = "CCTV$index"
                val label = context.getString(string.tv_iptv_channel_label, index, title, "央视")
                device.findObject(By.desc(label)).visibleBounds
            }
            assertEquals(4, cards.size)
            cards.zipWithNext().forEach { (left, right) ->
                assertTrue(right.left > left.right)
                assertTrue(kotlin.math.abs(left.centerY() - right.centerY()) <= 2)
            }
            val fifthLabel = context.getString(string.tv_iptv_channel_label, 5, "CCTV5", "央视")
            val fifth = device.findObject(By.desc(fifthLabel)).visibleBounds
            assertTrue(fifth.top > cards.first().bottom)
            assertTrue(kotlin.math.abs(fifth.centerX() - cards.first().centerX()) <= 2)

            assertTrue(device.pressDPadDown())
            waitForChannelFocus(index = 5)
            assertTrue(device.pressDPadUp())
            waitForChannelFocus(index = 1)
            assertTrue(device.pressDPadRight())
            waitForChannelFocus(index = 2)
            assertTrue(device.pressDPadLeft())
            waitForChannelFocus(index = 1)
        }
    }

    @Test
    fun playerUpDownSwitchChannelsAndRestoreTheLatestGridFocus() {
        launchOnGrid().use {
            waitForChannelFocus(index = 1)
            val closeLabel = context.getString(string.tv_action_close_player)
            assertTrue(device.pressDPadCenter())
            assertTrue(device.wait(Until.hasObject(By.desc(closeLabel)), timeout))
            assertTrue(device.pressDPadUp())
            assertTrue(device.pressBack())
            waitForChannelFocus(index = 2)

            assertTrue(device.pressDPadCenter())
            assertTrue(device.wait(Until.hasObject(By.desc(closeLabel)), timeout))
            assertTrue(device.pressDPadDown())
            assertTrue(device.pressBack())
            waitForChannelFocus(index = 1)

            assertTrue(device.pressDPadCenter())
            assertTrue(device.wait(Until.hasObject(By.desc(closeLabel)), timeout))
            assertTrue(device.pressDPadRight())
            refreshAccessibilityCache()
            val closeFocused = device.wait(Until.hasObject(By.desc(closeLabel).focused(true)), timeout)
            assertTrue("Expected Close focus; actual: ${focusedLabels()}", closeFocused)
            assertTrue(device.pressDPadUp())
            device.findObject(By.desc(closeLabel)).click()
            waitForChannelFocus(index = 2)
        }
    }

    @Test
    fun playerKeepsScreenAwakeWhileSwitchingAndRestoresItOnBackOrClose() {
        launchOnGrid().use { scenario ->
            waitForChannelFocus(index = 1)
            assertScreenKeptAwake(scenario, expected = false)
            val closeLabel = context.getString(string.tv_action_close_player)

            assertTrue(device.pressDPadCenter())
            assertTrue(device.wait(Until.hasObject(By.desc(closeLabel)), timeout))
            assertScreenKeptAwake(scenario, expected = true)
            assertTrue(device.pressDPadUp())
            assertScreenKeptAwake(scenario, expected = true)

            assertTrue(device.pressBack())
            waitForChannelFocus(index = 2)
            assertScreenKeptAwake(scenario, expected = false)

            assertTrue(device.pressDPadCenter())
            assertTrue(device.wait(Until.hasObject(By.desc(closeLabel)), timeout))
            assertScreenKeptAwake(scenario, expected = true)
            device.findObject(By.desc(closeLabel)).click()
            waitForChannelFocus(index = 2)
            assertScreenKeptAwake(scenario, expected = false)
        }
    }

    @Test
    fun playerKeepsScreenAwakeAcrossActivityRecreation() {
        launchOnGrid().use { scenario ->
            waitForChannelFocus(index = 1)
            val closeLabel = context.getString(string.tv_action_close_player)
            assertTrue(device.pressDPadCenter())
            assertTrue(device.wait(Until.hasObject(By.desc(closeLabel)), timeout))
            assertScreenKeptAwake(scenario, expected = true)

            scenario.recreate()
            assertTrue(device.wait(Until.hasObject(By.desc(closeLabel)), timeout))
            assertScreenKeptAwake(scenario, expected = true)

            assertTrue(device.pressBack())
            waitForChannelFocus(index = 1)
            assertScreenKeptAwake(scenario, expected = false)
        }
    }

    @Test
    fun controlsHideAfterThreeSecondsAndRemoteInputStillWorks() {
        launchOnGrid().use { scenario ->
            val playing = mutableStateOf(false)
            var pauseClicks = 0
            var previousClicks = 0
            var nextClicks = 0
            var backClicks = 0
            val closeLabel = context.getString(string.tv_action_close_player)
            scenario.onActivity { activity ->
                activity.setContent {
                    TvPlayerScreen(
                        player = null,
                        channel = Channel("https://example.test/live", "Test", "Test channel", playlistUrl = "test", id = 1),
                        isPlaying = playing.value,
                        playbackState = Player.STATE_READY,
                        onPlayPause = { pauseClicks++; playing.value = !playing.value },
                        onBack = { backClicks++ },
                        onClose = {},
                        onPreviousChannel = { previousClicks++ },
                        onNextChannel = { nextClicks++ },
                    )
                }
            }
            assertTrue(device.wait(Until.hasObject(By.desc(closeLabel)), timeout))
            val started = SystemClock.uptimeMillis()
            scenario.onActivity { playing.value = true }
            SystemClock.sleep(1_000)
            assertTrue("Controls must not hide immediately", device.hasObject(By.desc(closeLabel)))
            assertTrue(device.wait(Until.gone(By.desc(closeLabel)), 5_000))
            assertTrue("Expected a three-second display interval", SystemClock.uptimeMillis() - started >= 3_000)
            assertScreenKeptAwake(scenario, expected = true)

            assertTrue(device.pressDPadCenter())
            assertTrue(device.wait(Until.hasObject(By.desc(closeLabel)), timeout))
            scenario.onActivity { assertEquals(0, pauseClicks) }
            assertTrue(device.wait(Until.gone(By.desc(closeLabel)), 5_000))
            assertTrue(device.pressDPadRight())
            assertTrue(device.wait(Until.hasObject(By.desc(closeLabel)), timeout))
            assertTrue(device.wait(Until.gone(By.desc(closeLabel)), 5_000))
            assertTrue(device.pressDPadLeft())
            assertTrue(device.wait(Until.hasObject(By.desc(closeLabel)), timeout))
            assertTrue(device.wait(Until.gone(By.desc(closeLabel)), 5_000))

            assertTrue(device.pressDPadUp())
            assertTrue(device.wait(Until.hasObject(By.desc(closeLabel)), timeout))
            scenario.onActivity {
                assertEquals(1, nextClicks)
                assertEquals(0, previousClicks)
            }
            assertTrue(device.wait(Until.gone(By.desc(closeLabel)), 5_000))
            assertTrue(device.pressDPadDown())
            assertTrue(device.wait(Until.hasObject(By.desc(closeLabel)), timeout))
            scenario.onActivity {
                assertEquals(1, previousClicks)
                assertEquals(1, nextClicks)
            }
            assertTrue(device.wait(Until.gone(By.desc(closeLabel)), 5_000))
            device.pressBack()
            scenario.onActivity { assertEquals(1, backClicks) }
        }
    }

    @Test
    fun pausedBufferingAndErrorStatesKeepControlsVisible() {
        launchOnGrid().use { scenario ->
            val playing = mutableStateOf(true)
            val playbackState = mutableStateOf(Player.STATE_READY)
            val error = mutableStateOf<String?>(null)
            val closeLabel = context.getString(string.tv_action_close_player)
            scenario.onActivity { activity ->
                activity.setContent {
                    TvPlayerScreen(
                        player = null,
                        channel = null,
                        isPlaying = playing.value,
                        playbackState = playbackState.value,
                        errorMessage = error.value,
                        onPlayPause = { playing.value = !playing.value },
                        onBack = {},
                        onClose = {},
                        onPreviousChannel = {},
                        onNextChannel = {},
                    )
                }
            }
            assertTrue(device.wait(Until.gone(By.desc(closeLabel)), 5_000))
            scenario.onActivity { playing.value = false }
            assertTrue(device.wait(Until.hasObject(By.desc(closeLabel)), timeout))
            SystemClock.sleep(3_500)
            assertTrue(device.hasObject(By.desc(closeLabel)))

            scenario.onActivity { playing.value = true; playbackState.value = Player.STATE_BUFFERING }
            SystemClock.sleep(3_500)
            assertTrue(device.hasObject(By.desc(closeLabel)))
            scenario.onActivity { playbackState.value = Player.STATE_READY; error.value = "Test stream error" }
            SystemClock.sleep(3_500)
            assertTrue(device.hasObject(By.text("Test stream error")))
            assertTrue(device.hasObject(By.desc(closeLabel)))
            scenario.onActivity { error.value = null }
            assertTrue(device.wait(Until.gone(By.desc(closeLabel)), 5_000))
        }
    }

    @Test
    fun playerWrapsAndBackScrollsToAnOffscreenChannel() {
        launchOnGrid().use {
            waitForChannelFocus(index = 1)
            val closeLabel = context.getString(string.tv_action_close_player)
            assertTrue(device.pressDPadCenter())
            assertTrue(device.wait(Until.hasObject(By.desc(closeLabel)), timeout))
            assertTrue(device.pressDPadDown())
            assertTrue(device.pressBack())
            waitForChannelFocus(index = 140, title = "江苏导视", category = "地方")

            assertTrue(device.pressDPadCenter())
            assertTrue(device.wait(Until.hasObject(By.desc(closeLabel)), timeout))
            assertTrue(device.pressDPadUp())
            assertTrue(device.pressBack())
            waitForChannelFocus(index = 1)
        }
    }

    @Test
    fun numericInputFromGridDebouncesAndReturnsToCctv13() {
        launchOnGrid().use {
            waitForChannelFocus(index = 1)
            device.executeShellCommand("input keyevent 8 10")
            val pending = context.getString(string.tv_iptv_number_input, "13")
            assertTrue(device.wait(Until.hasObject(By.text(pending)), 1_000))
            val closeLabel = context.getString(string.tv_action_close_player)
            assertTrue(device.wait(Until.hasObject(By.desc(closeLabel)), timeout))
            assertTrue(device.pressBack())
            waitForChannelFocus(index = 13)
        }
    }

    @Test
    fun numpadAndOkSelectImmediatelyWithoutAnExtraPause() {
        launchOnGrid().use {
            waitForChannelFocus(index = 1)
            val started = SystemClock.uptimeMillis()
            device.executeShellCommand("input keyevent 145 147 23")
            val closeLabel = context.getString(string.tv_action_close_player)
            assertTrue(device.wait(Until.hasObject(By.desc(closeLabel)), 1_500))
            assertTrue("OK must not wait for the selection timer", SystemClock.uptimeMillis() - started < 1_500)
            assertTrue(device.pressBack())
            waitForChannelFocus(index = 13)
        }
    }

    @Test
    fun playerNumericInputSupportsCancelInvalidNumberAndSwitching() {
        launchOnGrid().use {
            waitForChannelFocus(index = 1)
            val closeLabel = context.getString(string.tv_action_close_player)
            assertTrue(device.pressDPadCenter())
            assertTrue(device.wait(Until.hasObject(By.desc(closeLabel)), timeout))

            device.executeShellCommand("input keyevent 9 4")
            SystemClock.sleep(1_700)
            device.pressBack()
            waitForChannelFocus(index = 1)

            assertTrue(device.pressDPadCenter())
            assertTrue(device.wait(Until.hasObject(By.desc(closeLabel)), timeout))
            device.executeShellCommand("input keyevent 7 23")
            val invalid = context.getString(string.tv_iptv_number_invalid, "0")
            assertTrue(device.wait(Until.hasObject(By.text(invalid)), 2_000))
            device.executeShellCommand("input keyevent 8 10 23")
            SystemClock.sleep(300)
            device.pressBack()
            waitForChannelFocus(index = 13)
        }
    }

    @Test
    fun qualitySwitchIsDpadAccessibleAndPersistsAcrossActivityLaunches() {
        val labels = listOf(
            string.tv_iptv_quality_1080p,
            string.tv_iptv_quality_4k_low,
            string.tv_iptv_quality_4k_high,
        ).map(context::getString)
        runBlocking {
            context.settings.edit {
                it[stringPreferencesKey("tv_iptv_quality")] = IptvQuality.HD.preferenceValue
            }
        }
        try {
            for (quality in IptvQuality.entries) {
                val next = quality.next()
                launchOnGrid().use {
                    waitForChannelFocus(index = 1)
                    assertTrue(device.hasObject(By.desc(labels[quality.ordinal])))
                    assertTrue(device.pressDPadUp())
                    assertTrue(device.pressDPadUp())
                    refreshAccessibilityCache()
                    assertTrue(device.wait(Until.hasObject(By.desc(labels[quality.ordinal]).focused(true)), timeout))
                    assertTrue(device.pressDPadCenter())
                    assertTrue(device.wait(Until.hasObject(By.desc(labels[next.ordinal])), timeout))
                    waitForChannelFocus(index = 1)
                }
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    assertActualPlayback(scenario, title = "CCTV1")
                    scenario.onActivity { activity ->
                        val viewModel = ViewModelProvider(activity)[TvIptvViewModel::class.java]
                        assertEquals(next, viewModel.state.value.quality)
                    }
                    assertTrue(device.pressBack())
                    waitForChannelFocus(index = 1)
                    assertTrue(device.hasObject(By.desc(labels[next.ordinal])))
                    scenario.recreate()
                    waitForChannelFocus(index = 1)
                    assertTrue(device.hasObject(By.desc(labels[next.ordinal])))
                }
            }
        } finally {
            runBlocking {
                context.settings.edit {
                    it[stringPreferencesKey("tv_iptv_quality")] = IptvQuality.HD.preferenceValue
                }
            }
        }
    }

    @Test
    fun legacyQualityMigratesToHighBitrateAndNewChoiceOverridesIt() {
        try {
            for (legacy in listOf(false, true)) {
                runBlocking {
                    context.settings.edit {
                        it.remove(stringPreferencesKey("tv_iptv_quality"))
                        it[booleanPreferencesKey("tv_iptv_prefer_4k")] = legacy
                    }
                }
                launchOnGrid().use { scenario ->
                    scenario.onActivity { activity ->
                        val viewModel = ViewModelProvider(activity)[TvIptvViewModel::class.java]
                        assertEquals(if (legacy) IptvQuality.UHD_HIGH else IptvQuality.HD, viewModel.state.value.quality)
                        viewModel.setQuality(IptvQuality.UHD_LOW)
                    }
                    val lowLabel = context.getString(string.tv_iptv_quality_4k_low)
                    assertTrue(device.wait(Until.hasObject(By.desc(lowLabel)), timeout))
                    runBlocking {
                        val preferences = context.settings.data.first()
                        assertEquals(IptvQuality.UHD_LOW.preferenceValue, preferences[stringPreferencesKey("tv_iptv_quality")])
                        assertFalse(preferences.contains(booleanPreferencesKey("tv_iptv_prefer_4k")))
                    }
                }
                launchOnGrid().use { scenario ->
                    scenario.onActivity { activity ->
                        assertEquals(IptvQuality.UHD_LOW, ViewModelProvider(activity)[TvIptvViewModel::class.java].state.value.quality)
                    }
                }
            }
        } finally {
            runBlocking {
                context.settings.edit {
                    it[stringPreferencesKey("tv_iptv_quality")] = IptvQuality.HD.preferenceValue
                    it.remove(booleanPreferencesKey("tv_iptv_prefer_4k"))
                }
            }
        }
    }

    @Test
    fun startupAutoplaysFirstChannelThenRestoresLastChannelWithoutShowingGrid() {
        runBlocking {
            context.settings.edit {
                it.remove(stringPreferencesKey("tv_iptv_last_channel_title"))
                it.remove(stringPreferencesKey("tv_iptv_quality"))
                it[booleanPreferencesKey("tv_iptv_prefer_4k")] = false
            }
        }
        val closeLabel = context.getString(string.tv_action_close_player)
        val count = context.resources.getQuantityString(plurals.tv_channel_count, 19, 19)
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue(device.wait(Until.hasObject(By.text("CCTV1")), timeout))
            assertFalse(device.hasObject(By.text(count)))
            device.executeShellCommand("input keyevent 8 10 23")
            assertTrue(device.wait(Until.hasObject(By.text("CCTV13")), timeout))
            assertFalse(device.hasObject(By.text(count)))
            assertTrue(device.pressBack())
            waitForChannelFocus(index = 13, title = "CCTV13")
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertTrue(device.wait(Until.hasObject(By.text("CCTV13")), timeout))
            assertFalse(device.hasObject(By.text(count)))
            assertScreenKeptAwake(scenario, expected = true)
            scenario.recreate()
            assertActualPlayback(scenario, title = "CCTV13")
            scenario.onActivity { activity ->
                ViewModelProvider(activity)[TvIptvViewModel::class.java].pauseOrContinue(false)
            }
            assertTrue(device.wait(Until.hasObject(By.desc(closeLabel)), timeout))
            assertFalse(device.hasObject(By.text(count)))
            device.findObject(By.desc(closeLabel)).click()
            waitForChannelFocus(index = 13, title = "CCTV13")
        }
    }

    @Test
    fun returningFromBackgroundReusesViewModelButActuallyRestartsLastPlayback() {
        launchOnGrid().use { scenario ->
            device.executeShellCommand("input keyevent 8 10 23")
            assertActualPlayback(scenario, title = "CCTV13")
            device.pressBack()
            waitForChannelFocus(index = 13)
            assertTrue(device.pressDPadRight())
            waitForChannelFocus(index = 14)
            var originalViewModel: TvIptvViewModel? = null
            scenario.onActivity { activity ->
                originalViewModel = ViewModelProvider(activity)[TvIptvViewModel::class.java]
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.onActivity { activity ->
                val viewModel = ViewModelProvider(activity)[TvIptvViewModel::class.java]
                assertFalse(viewModel.isPlaying.value)
                assertEquals(null, viewModel.player.value)
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertActualPlayback(scenario, title = "CCTV13")
            scenario.onActivity { activity ->
                assertSame(originalViewModel, ViewModelProvider(activity)[TvIptvViewModel::class.java])
            }
            val count = context.resources.getQuantityString(plurals.tv_channel_count, 140, 140)
            assertFalse(device.hasObject(By.text(count)))
            assertScreenKeptAwake(scenario, expected = true)
            device.pressBack()
            waitForChannelFocus(index = 13)
        }
    }

    @Test
    fun pausedPlaybackStaysPausedDuringConfigurationRecreation() {
        launchOnGrid().use { scenario ->
            assertTrue(device.pressDPadCenter())
            assertActualPlayback(scenario, title = "CCTV1")
            scenario.onActivity { activity ->
                ViewModelProvider(activity)[TvIptvViewModel::class.java].pauseOrContinue(false)
            }
            scenario.recreate()
            assertTrue(device.wait(Until.hasObject(By.desc(context.getString(string.tv_action_play))), timeout))
            scenario.onActivity { activity ->
                val viewModel = ViewModelProvider(activity)[TvIptvViewModel::class.java]
                assertEquals("CCTV1", viewModel.state.value.playingChannel?.title)
                assertFalse(viewModel.isPlaying.value)
                assertFalse(checkNotNull(viewModel.player.value).playWhenReady)
            }
        }
    }

    @Test
    fun stoppingActivityCancelsPendingNumericSelection() {
        launchOnGrid().use { scenario ->
            waitForChannelFocus(index = 1)
            device.executeShellCommand("input keyevent 9")
            scenario.moveToState(Lifecycle.State.CREATED)
            SystemClock.sleep(1_700)
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertActualPlayback(scenario, title = "CCTV1")
            device.pressBack()
            waitForChannelFocus(index = 1)
            assertFalse(device.hasObject(By.desc(context.getString(string.tv_action_close_player))))
        }
    }

    private fun assertActualPlayback(scenario: ActivityScenario<MainActivity>, title: String) {
        val deadline = SystemClock.uptimeMillis() + timeout
        var playing = false
        var position = 0L
        while (!playing && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { activity ->
                val viewModel = ViewModelProvider(activity)[TvIptvViewModel::class.java]
                val player = viewModel.player.value
                playing = viewModel.state.value.playingChannel?.title == title &&
                    viewModel.isPlaying.value && player?.isPlaying == true
                if (playing) position = checkNotNull(player).currentPosition
            }
            if (!playing) SystemClock.sleep(100)
        }
        assertTrue("Expected actual playback of $title, not just channel selection", playing)
        SystemClock.sleep(1_000)
        scenario.onActivity { activity ->
            val player = checkNotNull(ViewModelProvider(activity)[TvIptvViewModel::class.java].player.value)
            assertTrue(player.isPlaying)
            assertTrue("Playback position must advance", player.currentPosition > position)
        }
    }

    private fun launchOnGrid(tab: TvIptvTab = TvIptvTab.All): ActivityScenario<MainActivity> {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val closeLabel = context.getString(string.tv_action_close_player)
            assertTrue(device.wait(Until.hasObject(By.desc(closeLabel)), timeout))
            device.executeShellCommand("input keyevent 8 23")
            assertTrue(device.wait(Until.hasObject(By.text("CCTV1")), timeout))
            assertTrue(device.pressBack())
            waitForChannelFocus(index = 1)
            if (tab != TvIptvTab.Favorite) {
                assertTrue(device.pressDPadUp())
                val favoriteLabel = context.getString(string.tv_iptv_tab_favorite)
                waitForFocus(favoriteLabel)
                assertTrue(device.pressDPadRight())
                val allLabel = context.getString(string.tv_iptv_tab_all)
                waitForFocus(allLabel)
                assertTrue(device.pressDPadCenter())
                waitForChannelFocus(index = 1)
            }
            return scenario
        } catch (error: Throwable) {
            scenario.close()
            throw error
        }
    }

    private fun waitForFocus(label: String) {
        val deadline = SystemClock.uptimeMillis() + timeout
        do {
            refreshAccessibilityCache()
            if (device.hasObject(By.desc(label).focused(true))) return
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        assertTrue("Expected focus on $label; actual: ${focusedLabels()}", false)
    }

    private fun waitForChannelFocus(index: Int, title: String = "CCTV$index", category: String = "央视") {
        val label = context.getString(string.tv_iptv_channel_label, index, title, category)
        waitForFocus(label)
    }

    private fun focusedLabels(): List<String?> =
        device.findObjects(By.focused(true)).map { it.contentDescription }

    private fun assertScreenKeptAwake(scenario: ActivityScenario<MainActivity>, expected: Boolean) {
        scenario.onActivity { activity ->
            assertEquals(expected, keepsScreenAwake(activity.window.decorView))
        }
    }

    private fun keepsScreenAwake(view: View): Boolean {
        if (view.keepScreenOn) return true
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                if (keepsScreenAwake(view.getChildAt(index))) return true
            }
        }
        return false
    }

    private fun refreshAccessibilityCache() {
        device.waitForIdle()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Focus updates on retained Compose nodes can leave Android's cached flags stale.
            instrumentation.uiAutomation.clearCache()
        }
    }

}
