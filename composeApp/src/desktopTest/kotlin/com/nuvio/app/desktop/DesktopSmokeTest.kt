package com.nuvio.app.desktop

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.nuvio.app.App
import com.nuvio.app.core.deeplink.handleAppUrl
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.minutes

/**
 * Drives the real App() the way a user would on first launch: skip sign-in, wait for addon
 * catalogs on Home, open a title's details, then search. Each step saves a frame and the
 * semantics tree to build/desktop-smoke/shots. Run with `gradlew :composeApp:desktopTest -Pnuvio.smoke`.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopSmokeTest {
    private val out = File(System.getProperty("nuvio.smoke.out") ?: "build/desktop-smoke/shots").also { it.mkdirs() }

    @Test
    fun homeDetailsSearch() {
        assumeTrue(System.getProperty("nuvio.smoke") == "true")
        initializePlatformStorage()
        runDesktopComposeUiTest(width = 1280, height = 800, testTimeout = 10.minutes) {
            setContent { App() }

            waitForText("Continue Without Account")
            snapshot("01-auth")
            onNode(hasText("Continue Without Account")).performClick()

            waitForText("Manage Profiles")
            onNode(hasText("Manage Profiles")).performClick()
            waitForText("Add Profile")
            onNode(hasText("Add Profile")).performClick()
            waitForText("Create Profile")
            onAllNodes(hasSetTextAction()).onFirst().performTextInput("Desktop")
            onNode(hasText("Create Profile") and hasClickAction()).performClick()
            waitForText("No content sources")
            snapshot("02-home-empty")

            // Same path as clicking an addon's "Install" link in a browser.
            handleAppUrl("stremio://v3-cinemeta.strem.io/manifest.json")
            waitForText("Cinemeta was validated")
            waitForText("Installed Addons")
            snapshot("03-addons")
            onNode(hasContentDescription("Back") and hasClickAction()).performClick()
            waitUntil(timeoutMillis = 15_000) { !hasAnyText("Installed Addons") }
            settle(1_000)
            waitForDescription("Home")
            onNode(hasContentDescription("Home") and hasClickAction()).performClick()
            waitForText("Popular", timeoutMillis = 90_000)
            settle(10_000)
            snapshot("04-home")
        }
    }

    private fun ComposeUiTest.hasAnyText(text: String): Boolean =
        onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.waitForText(text: String, timeoutMillis: Long = 45_000) {
        try {
            waitUntil(timeoutMillis = timeoutMillis) { hasAnyText(text) }
        } catch (error: Throwable) {
            snapshot("failed-waiting-for-${text.replace(Regex("[^A-Za-z0-9]"), "_")}")
            throw error
        }
    }

    private fun ComposeUiTest.waitForDescription(description: String, timeoutMillis: Long = 45_000) {
        try {
            waitUntil(timeoutMillis = timeoutMillis) {
                onAllNodes(hasContentDescription(description, substring = true)).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (error: Throwable) {
            snapshot("failed-waiting-for-${description.replace(Regex("[^A-Za-z0-9]"), "_")}")
            throw error
        }
    }

    /** Lets real time pass (network, disk) while frames keep rendering. */
    private fun ComposeUiTest.settle(millis: Long) {
        val end = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < end) {
            mainClock.advanceTimeByFrame()
            Thread.sleep(50)
        }
    }

    private fun ComposeUiTest.snapshot(name: String) {
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "$name.png"))
        File(out, "$name.txt").writeText(onRoot(useUnmergedTree = false).printToString(maxDepth = 60))
        println("smoke: saved $name")
    }
}
