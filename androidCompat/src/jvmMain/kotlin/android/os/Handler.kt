package android.os

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Android's Looper as a single thread that runs posted work in order. The main looper is a thread
 * of its own, not the desktop UI thread, because extensions only use it for timers and callbacks.
 */
class Looper private constructor(name: String) {
    @Volatile
    private var thread: Thread? = null

    internal val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, name).apply {
            isDaemon = true
            thread = this
        }
    }

    fun getThread(): Thread? = thread
    fun isCurrentThread(): Boolean = Thread.currentThread() == thread
    fun quit() = executor.shutdownNow().let { }
    fun quitSafely() = executor.shutdown()

    companion object {
        private val main = Looper("Android main looper")
        private val current = ThreadLocal<Looper?>()

        @JvmStatic
        fun getMainLooper(): Looper = main

        /** The looper of this thread: the main looper on its own thread, or one made by prepare() */
        @JvmStatic
        fun myLooper(): Looper? = if (main.isCurrentThread()) main else current.get()

        @JvmStatic
        fun prepare() {
            if (current.get() == null) current.set(Looper("Android looper ${Thread.currentThread().name}"))
        }

        @JvmStatic
        fun prepareMainLooper() {}

        /** Work runs on the looper's own thread, so there is nothing to wait for here */
        @JvmStatic
        fun loop() {}
    }
}

open class Handler(private val looper: Looper) {
    /** Android needs a looper on this thread, desktop falls back to the main looper */
    constructor() : this(Looper.myLooper() ?: Looper.getMainLooper())

    private class Posted(val runnable: Runnable, val token: Any?, val future: ScheduledFuture<*>)

    private val posted = mutableListOf<Posted>()

    open fun getLooper(): Looper = looper

    open fun post(r: Runnable?): Boolean = postDelayed(r, 0L)

    open fun postDelayed(r: Runnable?, delayMillis: Long): Boolean = postDelayed(r, null, delayMillis)

    open fun postDelayed(r: Runnable?, token: Any?, delayMillis: Long): Boolean {
        if (r == null) return false
        return try {
            synchronized(posted) {
                lateinit var entry: Posted
                val future = looper.executor.schedule({
                    synchronized(posted) { posted.remove(entry) }
                    try {
                        r.run()
                    } catch (t: Throwable) {
                        println("ERROR Handler: Posted work failed: $t")
                        t.printStackTrace()
                    }
                }, maxOf(0L, delayMillis), TimeUnit.MILLISECONDS)
                entry = Posted(r, token, future)
                posted += entry
            }
            true
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            false
        }
    }

    open fun postAtFrontOfQueue(r: Runnable?): Boolean = post(r)

    open fun hasCallbacks(r: Runnable?): Boolean = synchronized(posted) { posted.any { it.runnable == r } }

    open fun removeCallbacks(r: Runnable?) = remove { it.runnable == r }

    open fun removeCallbacks(r: Runnable?, token: Any?) = remove { it.runnable == r && (token == null || it.token == token) }

    /** A null token removes everything, like Android */
    open fun removeCallbacksAndMessages(token: Any?) = remove { token == null || it.token == token }

    private fun remove(match: (Posted) -> Boolean) = synchronized(posted) {
        val removed = posted.filter(match)
        removed.forEach { it.future.cancel(false) }
        posted.removeAll(removed)
        Unit
    }
}
