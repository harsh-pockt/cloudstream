package com.lagradost.cloudstream4

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput

/**
 * Knows whether a text field is being typed in. The letters typed into one don't come as key events,
 * so a key press can't tell: the text fields' input sessions can. Every text field below [content]
 * opens its session through here, and the platform's input still does the work.
 */
class TypingTracker {
    private var sessions = 0

    val active: Boolean get() = sessions > 0

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    fun Provide(content: @Composable () -> Unit) = InterceptPlatformTextInput({ request, next ->
        sessions++
        try {
            next.startInputMethod(request)
        } finally {
            sessions--
        }
    }, content)
}
