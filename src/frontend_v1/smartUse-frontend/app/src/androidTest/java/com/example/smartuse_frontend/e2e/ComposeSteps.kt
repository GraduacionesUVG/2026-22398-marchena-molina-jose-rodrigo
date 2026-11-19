package com.example.smartuse_frontend.e2e

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick

fun ComposeTestRule.waitForText(text: String, timeoutMs: Long = 15_000) =
    waitUntil(timeoutMs) { onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

fun ComposeTestRule.tapText(text: String) = onAllNodes(hasText(text) and hasClickAction()).onFirst().performClick()

fun ComposeTestRule.count(text: String, substring: Boolean = false): Int =
    onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().size
