package com.lagradost.cloudstream4.player

import kotlinx.coroutines.flow.Flow

/**
 * Sites whose links wait on a check only a person can pass, such as Cloudflare's tick box. Their links
 * come last: the user is only asked to pass the checks once nothing else is left to play.
 */
interface HumanChecks {
    /** A site's host, each time one asks for a check that did not pass by itself */
    val asked: Flow<String>

    /** Lets the user pass the site's check, in a window. True once passed */
    suspend fun pass(host: String): Boolean
}
