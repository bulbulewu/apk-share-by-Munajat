package com.example

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReceiverPermissionCrashTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `navigating to Receive screen and launching runtime permissions does not crash FragmentActivity`() {
        composeRule.waitUntil(timeoutMillis = 5000) {
            runCatching {
                composeRule.onNodeWithTag("receive_button").fetchSemanticsNode()
                true
            }.getOrDefault(false)
        }
        composeRule.onNodeWithTag("receive_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        // Verify ReceiverScreen rendered without throwing IllegalArgumentException or SecurityException
        composeRule.onNodeWithTag("receiver_back_button").assertExists()
        composeRule.onNodeWithTag("permission_rationale_card").assertExists()
        composeRule.onNodeWithTag("grant_permissions_button").assertExists()
        composeRule.onNodeWithTag("open_app_settings_button").assertExists()
    }
}
