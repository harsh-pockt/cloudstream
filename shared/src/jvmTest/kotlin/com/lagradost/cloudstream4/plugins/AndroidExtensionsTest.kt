package com.lagradost.cloudstream4.plugins

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream4.android.DesktopAndroid
import com.lagradost.cloudstream4.android.DexConverter
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.io.path.createDirectories
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.test.Test

/**
 * Converts, loads and searches with every Android extension (.cs3) in a folder, and reports how
 * many work on desktop. It needs the internet and the extensions, so it only runs when
 * CLOUDSTREAM_ANDROID_EXTENSIONS is set to the folder. The report goes to build/android-extensions.
 */
class AndroidExtensionsTest {
    private enum class Outcome {
        /** A provider returned search results */
        WORKS,
        /** Searches ran but found nothing, which is often a site that blocks or changed */
        NO_RESULTS,
        /** The site or network failed, not desktop */
        SITE_ERROR,
        /** Every search ran past the hard limit */
        TIMED_OUT,
        /** A class or member desktop lacks */
        MISSING_ON_DESKTOP,
        /** Loaded, but registered no provider to search with, such as extractor-only plugins */
        NO_PROVIDER,
        LOAD_FAILED,
        CONVERSION_FAILED,
    }

    private class Report(val name: String) {
        var outcome = Outcome.LOAD_FAILED
        var detail = ""
        var convertMillis = 0L
        var failedMethods = 0
        var providers = listOf<String>()
        var results = 0
        var stubbed = setOf<String>()
    }

    /**
     * Runs [block] on its own thread and gives up on it after [limitMs]. Some extensions block in
     * code a coroutine timeout cannot stop (a socket read without a timeout, a busy loop), which
     * once held a full run for over 90 minutes; an abandoned thread is a daemon and is left behind.
     */
    private fun <T> withHardLimit(limitMs: Long, what: String, block: suspend () -> T): T {
        val future = hardLimitThreads.submit<T> { runBlocking { block() } }
        try {
            return future.get(limitMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw HardLimitException("$what took over ${limitMs / 1000} s")
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    private class HardLimitException(message: String) : Exception(message)

    private val hardLimitThreads = Executors.newCachedThreadPool { task ->
        Thread(task, "extension-test").apply { isDaemon = true }
    }

    private fun Throwable.isMissingOnDesktop(): Boolean =
        generateSequence(this) { it.cause }.any { it is LinkageError || it is AbstractMethodError || it is ClassCastException }

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    @Test
    fun androidExtensionsWorkOnDesktop() = runBlocking<Unit> {
        val folder = System.getenv("CLOUDSTREAM_ANDROID_EXTENSIONS") ?: return@runBlocking println("Skipped, set CLOUDSTREAM_ANDROID_EXTENSIONS")
        // Common words in a few languages and a popular title, since sites search in their own language
        val queries = System.getenv("CLOUDSTREAM_SEARCH_QUERY")?.split(',') ?: listOf("the", "a", "naruto", "love")
        // The app has Swing's main dispatcher, extensions use Dispatchers.Main when they load
        Dispatchers.setMain(newSingleThreadContext("Test main"))
        val work = Files.createTempDirectory("cs-android-extensions")
        DesktopAndroid.init(work.resolve("android"))
        val out = Path.of("build", "android-extensions").createDirectories()
        val files = Path.of(folder).listDirectoryEntries("*.cs3").sortedBy { it.name }
        println("Testing ${files.size} extensions, searching for $queries")

        // Convert and load one at a time, plugins register into one global list
        val reports = files.map { cs3 ->
            val report = Report(cs3.name.removeSuffix(".cs3"))
            val jar = work.resolve(cs3.name.removeSuffix(".cs3") + ".jar")
            try {
                val start = System.nanoTime()
                report.failedMethods = DexConverter.convert(cs3, jar).failedMethods
                report.convertMillis = (System.nanoTime() - start) / 1_000_000
            } catch (t: Throwable) {
                report.outcome = Outcome.CONVERSION_FAILED
                report.detail = t.toString()
                return@map report to null
            }
            val loaded = try {
                withHardLimit(LOAD_LIMIT_MS, "Loading") { PluginLoader.load(jar) }
            } catch (t: Throwable) {
                report.outcome = if (t.isMissingOnDesktop()) Outcome.MISSING_ON_DESKTOP else Outcome.LOAD_FAILED
                report.detail = "load: $t"
                return@map report to null
            }
            report.providers = loaded.providers.map { it.name }
            report to loaded
        }

        val searches = Semaphore(12)
        reports.map { (report, loaded) ->
            async(Dispatchers.IO) {
                if (loaded == null) return@async
                val providers = loaded.providers
                if (providers.isEmpty()) {
                    report.outcome = Outcome.NO_PROVIDER
                    return@async
                }
                val errors = mutableListOf<Throwable>()
                for (provider in providers) {
                    for (query in queries) {
                        val items = searches.withPermit {
                            try {
                                withHardLimit(SEARCH_LIMIT_MS, "Searching") { provider.search(query, 1)?.items.orEmpty() }
                            } catch (t: Throwable) {
                                errors += t
                                emptyList()
                            }
                        }
                        report.results += items.size
                        if (items.isNotEmpty()) break
                    }
                }
                report.outcome = when {
                    report.results > 0 -> Outcome.WORKS
                    errors.any { it.isMissingOnDesktop() } -> Outcome.MISSING_ON_DESKTOP
                    errors.isNotEmpty() && errors.all { it is HardLimitException } -> Outcome.TIMED_OUT
                    errors.isNotEmpty() -> Outcome.SITE_ERROR
                    else -> Outcome.NO_RESULTS
                }
                report.detail = (errors.firstOrNull { it.isMissingOnDesktop() } ?: errors.firstOrNull())?.let {
                    it.toString() + " at " + it.stackTrace.take(3).joinToString(" < ")
                } ?: ""
            }
        }.awaitAll()
        for ((report, loaded) in reports) {
            report.stubbed = loaded?.stubbedClasses.orEmpty()
            runCatching { loaded?.unload() }
        }

        val all = reports.map { it.first }
        val json = JsonArray(all.map {
            JsonObject(
                mapOf(
                    "name" to JsonPrimitive(it.name),
                    "outcome" to JsonPrimitive(it.outcome.name),
                    "detail" to JsonPrimitive(it.detail),
                    "convertMillis" to JsonPrimitive(it.convertMillis),
                    "failedMethods" to JsonPrimitive(it.failedMethods),
                    "providers" to JsonArray(it.providers.map(::JsonPrimitive)),
                    "results" to JsonPrimitive(it.results),
                    "stubbed" to JsonArray(it.stubbed.sorted().map(::JsonPrimitive)),
                )
            )
        })
        out.resolve("report.json").writeText(json.toString())
        val counts = all.groupingBy { it.outcome }.eachCount()
        println("Results for ${all.size} extensions:")
        Outcome.entries.forEach { println("  ${it.name}: ${counts[it] ?: 0}") }
        println("Report: ${out.resolve("report.json").toAbsolutePath()}")
        work.toFile().deleteRecursively()
        APIHolder.allProviders.clear()
        hardLimitThreads.shutdownNow()
    }

    companion object {
        private const val SEARCH_LIMIT_MS = 30_000L
        private const val LOAD_LIMIT_MS = 60_000L
    }
}
