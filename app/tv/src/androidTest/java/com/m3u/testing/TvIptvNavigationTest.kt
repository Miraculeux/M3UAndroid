package com.m3u.testing

import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
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
import com.m3u.data.database.model.Channel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class TvIptvNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val timeout = 15_000L

    @Test
    fun startupImportsAllChannelsWithoutDuplicatingThemOnRelaunch() {
        repeat(2) {
            ActivityScenario.launch(MainActivity::class.java).use {
                waitForChannelFocus(index = 1)
                val count = context.resources.getQuantityString(plurals.tv_channel_count, 150, 150)
                assertTrue(device.hasObject(By.text(count)))
            }
        }
    }

    @Test
    fun startupShowsOnlyTheChannelListWithInitialDpadFocus() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForChannelFocus(index = 1)
            val count = context.resources.getQuantityString(plurals.tv_channel_count, 150, 150)
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
        ActivityScenario.launch(MainActivity::class.java).use {
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
        ActivityScenario.launch(MainActivity::class.java).use {
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
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForChannelFocus(index = 1)
            val closeLabel = context.getString(string.tv_action_close_player)
            assertTrue(device.pressDPadCenter())
            assertTrue(device.wait(Until.hasObject(By.desc(closeLabel)), timeout))
            assertTrue(device.pressDPadDown())
            assertTrue(device.pressBack())
            waitForChannelFocus(index = 2)

            assertTrue(device.pressDPadCenter())
            assertTrue(device.wait(Until.hasObject(By.desc(closeLabel)), timeout))
            assertTrue(device.pressDPadUp())
            assertTrue(device.pressBack())
            waitForChannelFocus(index = 1)

            assertTrue(device.pressDPadCenter())
            assertTrue(device.wait(Until.hasObject(By.desc(closeLabel)), timeout))
            assertTrue(device.pressDPadRight())
            refreshAccessibilityCache()
            val closeFocused = device.wait(Until.hasObject(By.desc(closeLabel).focused(true)), timeout)
            assertTrue("Expected Close focus; actual: ${focusedLabels()}", closeFocused)
            assertTrue(device.pressDPadDown())
            device.findObject(By.desc(closeLabel)).click()
            waitForChannelFocus(index = 2)
        }
    }

    @Test
    fun playerKeepsScreenAwakeWhileSwitchingAndRestoresItOnBackOrClose() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForChannelFocus(index = 1)
            assertScreenKeptAwake(scenario, expected = false)
            val closeLabel = context.getString(string.tv_action_close_player)

            assertTrue(device.pressDPadCenter())
            assertTrue(device.wait(Until.hasObject(By.desc(closeLabel)), timeout))
            assertScreenKeptAwake(scenario, expected = true)
            assertTrue(device.pressDPadDown())
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
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
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
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
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
            scenario.onActivity { assertEquals(1, previousClicks) }
            assertTrue(device.wait(Until.gone(By.desc(closeLabel)), 5_000))
            assertTrue(device.pressDPadDown())
            assertTrue(device.wait(Until.hasObject(By.desc(closeLabel)), timeout))
            scenario.onActivity { assertEquals(1, nextClicks) }
            assertTrue(device.wait(Until.gone(By.desc(closeLabel)), 5_000))
            device.pressBack()
            scenario.onActivity { assertEquals(1, backClicks) }
        }
    }

    @Test
    fun pausedBufferingAndErrorStatesKeepControlsVisible() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
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
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForChannelFocus(index = 1)
            val closeLabel = context.getString(string.tv_action_close_player)
            assertTrue(device.pressDPadCenter())
            assertTrue(device.wait(Until.hasObject(By.desc(closeLabel)), timeout))
            assertTrue(device.pressDPadUp())
            assertTrue(device.pressBack())
            waitForChannelFocus(index = 150, title = "江苏健康广播", category = "广播")

            assertTrue(device.pressDPadCenter())
            assertTrue(device.wait(Until.hasObject(By.desc(closeLabel)), timeout))
            assertTrue(device.pressDPadDown())
            assertTrue(device.pressBack())
            waitForChannelFocus(index = 1)
        }
    }

    @Test
    fun numericInputFromGridDebouncesAndReturnsToCctv13() {
        ActivityScenario.launch(MainActivity::class.java).use {
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
        ActivityScenario.launch(MainActivity::class.java).use {
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
        ActivityScenario.launch(MainActivity::class.java).use {
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
        val hdLabel = context.getString(string.tv_iptv_quality_1080p)
        val uhdLabel = context.getString(string.tv_iptv_quality_4k)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                waitForChannelFocus(index = 1)
                if (device.hasObject(By.desc(uhdLabel))) {
                    device.findObject(By.desc(uhdLabel)).click()
                    waitForChannelFocus(index = 1)
                }
                assertTrue(device.hasObject(By.desc(hdLabel)))
                assertTrue(device.pressDPadUp())
                refreshAccessibilityCache()
                assertTrue(device.wait(Until.hasObject(By.desc(hdLabel).focused(true)), timeout))
                assertTrue(device.pressDPadCenter())
                assertTrue(device.wait(Until.hasObject(By.desc(uhdLabel)), timeout))
                waitForChannelFocus(index = 1)
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitForChannelFocus(index = 1)
                assertTrue(device.hasObject(By.desc(uhdLabel)))
                scenario.recreate()
                waitForChannelFocus(index = 1)
                assertTrue(device.hasObject(By.desc(uhdLabel)))
                device.executeShellCommand("input keyevent 8 13 23")
                val closeLabel = context.getString(string.tv_action_close_player)
                assertTrue(device.wait(Until.hasObject(By.desc(closeLabel)), timeout))
                assertTrue(device.pressBack())
                waitForChannelFocus(index = 16)
                device.findObject(By.desc(uhdLabel)).click()
                assertTrue(device.wait(Until.hasObject(By.desc(hdLabel)), timeout))
                waitForChannelFocus(index = 16)
            }
            ActivityScenario.launch(MainActivity::class.java).use {
                waitForChannelFocus(index = 1)
                assertTrue(device.hasObject(By.desc(hdLabel)))
            }
        } finally {
            ActivityScenario.launch(MainActivity::class.java).use {
                waitForChannelFocus(index = 1)
                if (device.hasObject(By.desc(uhdLabel))) {
                    device.findObject(By.desc(uhdLabel)).click()
                    waitForChannelFocus(index = 1)
                }
            }
        }
    }

    @Test
    fun stoppingActivityCancelsPendingNumericSelection() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForChannelFocus(index = 1)
            device.executeShellCommand("input keyevent 9")
            scenario.moveToState(Lifecycle.State.CREATED)
            SystemClock.sleep(1_700)
            scenario.moveToState(Lifecycle.State.RESUMED)
            waitForChannelFocus(index = 1)
            assertFalse(device.hasObject(By.desc(context.getString(string.tv_action_close_player))))
        }
    }

    private fun waitForChannelFocus(index: Int, title: String = "CCTV$index", category: String = "央视") {
        val label = context.getString(string.tv_iptv_channel_label, index, title, category)
        refreshAccessibilityCache()
        val focused = device.wait(Until.hasObject(By.desc(label).focused(true)), timeout)
        assertTrue(
            "Expected DPad focus on channel $index; actual: ${focusedLabels()}",
            focused,
        )
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
