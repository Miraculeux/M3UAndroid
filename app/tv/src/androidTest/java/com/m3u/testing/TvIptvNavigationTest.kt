package com.m3u.testing

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
            assertTrue(device.pressDPadDown())
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

    private fun waitForChannelFocus(index: Int) {
        val label = context.getString(string.tv_iptv_channel_label, index, "CCTV1", "央视")
        assertTrue(
            "Expected DPad focus on channel $index",
            device.wait(Until.hasObject(By.desc(label).focused(true)), timeout),
        )
    }

}
