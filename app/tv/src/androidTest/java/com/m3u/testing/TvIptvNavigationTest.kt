package com.m3u.testing

import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.m3u.i18n.R.plurals
import com.m3u.i18n.R.string
import com.m3u.tv.MainActivity
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
                val count = context.resources.getQuantityString(plurals.tv_channel_count, 191, 191)
                assertTrue(device.hasObject(By.text(count)))
            }
        }
    }

    @Test
    fun startupShowsOnlyTheChannelListWithInitialDpadFocus() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForChannelFocus(index = 1)
            val count = context.resources.getQuantityString(plurals.tv_channel_count, 191, 191)
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
                val title = if (index == 4) "CCTV2" else "CCTV1"
                val label = context.getString(string.tv_iptv_channel_label, index, title, "央视")
                device.findObject(By.desc(label)).visibleBounds
            }
            assertEquals(4, cards.size)
            cards.zipWithNext().forEach { (left, right) ->
                assertTrue(right.left > left.right)
                assertTrue(kotlin.math.abs(left.centerY() - right.centerY()) <= 2)
            }
            val fifthLabel = context.getString(string.tv_iptv_channel_label, 5, "CCTV2", "央视")
            val fifth = device.findObject(By.desc(fifthLabel)).visibleBounds
            assertTrue(fifth.top > cards.first().bottom)
            assertTrue(kotlin.math.abs(fifth.centerX() - cards.first().centerX()) <= 2)

            assertTrue(device.pressDPadDown())
            waitForChannelFocus(index = 5, title = "CCTV2")
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
    fun playerWrapsAndBackScrollsToAnOffscreenChannel() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForChannelFocus(index = 1)
            val closeLabel = context.getString(string.tv_action_close_player)
            assertTrue(device.pressDPadCenter())
            assertTrue(device.wait(Until.hasObject(By.desc(closeLabel)), timeout))
            assertTrue(device.pressDPadUp())
            assertTrue(device.pressBack())
            waitForChannelFocus(index = 191, title = "江苏健康广播", category = "广播")

            assertTrue(device.pressDPadCenter())
            assertTrue(device.wait(Until.hasObject(By.desc(closeLabel)), timeout))
            assertTrue(device.pressDPadDown())
            assertTrue(device.pressBack())
            waitForChannelFocus(index = 1)
        }
    }

    private fun waitForChannelFocus(index: Int, title: String = "CCTV1", category: String = "央视") {
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
