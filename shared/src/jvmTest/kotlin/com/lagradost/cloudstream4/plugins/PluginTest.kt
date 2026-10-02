package com.lagradost.cloudstream4.plugins

import com.lagradost.cloudstream3.APIHolder
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.exists
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PluginTest {
    private val dir: Path = Files.createTempDirectory("cs-plugins")
    private var server: HttpServer? = null

    @AfterTest
    fun cleanup() {
        server?.stop(0)
        dir.toFile().deleteRecursively()
    }

    private fun classBytes(name: String): ByteArray =
        javaClass.classLoader.getResourceAsStream(name.replace('.', '/') + ".class")!!.use { it.readBytes() }

    /** A jar laid out like the ones the gradle plugin publishes: classes only, no manifest.json */
    private fun pluginJar(vararg classes: String, manifest: String? = null): ByteArray {
        val out = ByteArrayOutputStream()
        JarOutputStream(out).use { jar ->
            for (name in classes) {
                jar.putNextEntry(JarEntry(name.replace('.', '/') + ".class"))
                jar.write(classBytes(name))
            }
            if (manifest != null) {
                jar.putNextEntry(JarEntry("manifest.json"))
                jar.write(manifest.toByteArray())
            }
        }
        return out.toByteArray()
    }

    private fun writeJar(bytes: ByteArray): Path = dir.resolve("plugin-${System.nanoTime()}.jar").also { it.writeBytes(bytes) }

    private val pluginClasses = arrayOf(NotAPlugin::class.java.name, TestPlugin::class.java.name, TestProvider::class.java.name)

    @Test
    fun findsThePluginClassByItsAnnotation() {
        val jar = writeJar(pluginJar(*pluginClasses))
        assertEquals(TestPlugin::class.java.name, PluginLoader.findPluginClassName(jar))
    }

    @Test
    fun manifestJsonWinsWhenPresent() {
        val jar = writeJar(pluginJar(*pluginClasses, manifest = """{"pluginClassName":"some.Other"}"""))
        assertEquals("some.Other", PluginLoader.findPluginClassName(jar))
    }

    @Test
    fun jarWithoutAPluginIsRejected() {
        val jar = writeJar(pluginJar(NotAPlugin::class.java.name))
        assertFailsWith<IllegalArgumentException> { PluginLoader.load(jar) }
    }

    @Test
    fun loadRegistersProvidersAndUnloadRemovesThem() {
        val jar = writeJar(pluginJar(*pluginClasses))
        val before = TestPlugin.unloads

        val plugin = PluginLoader.load(jar)
        assertEquals(listOf("Desktop Test Provider"), plugin.providers.map { it.name })
        assertNotNull(APIHolder.getApiFromNameNull("Desktop Test Provider"))

        plugin.unload()
        assertEquals(before + 1, TestPlugin.unloads)
        assertTrue(plugin.providers.isEmpty())
        assertTrue(APIHolder.apis.none { it.name == "Desktop Test Provider" })
        // The class loader is closed, so Windows lets the jar be deleted
        Files.delete(jar)
    }

    @Test
    fun parsesPluginListsWithAndWithoutDesktopJars() {
        val plugins = RepositoryClient.parsePluginList(
            """[
              {"internalName":"A","name":"A","version":3,"status":1,"authors":["x"],"jarUrl":"https://h/A.jar","jarHash":"sha256-00","jarFileSize":10},
              {"internalName":"B","name":"B","version":1,"status":0,"url":"https://h/B.cs3"},
              {"name":"no internal name"}
            ]"""
        )
        assertEquals(listOf("A", "B"), plugins.map { it.internalName })
        assertTrue(plugins[0].supportsDesktop)
        assertFalse(plugins[1].supportsDesktop)
        assertEquals(10L, plugins[0].jarFileSize)
    }

    /** Serves repo.json, plugins.json and the jar from a local server, like a GitHub repository */
    private fun serveRepository(jar: ByteArray, jarHash: String = sha256(jar)): String {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { this.server = it }
        val base = "http://127.0.0.1:${server.address.port}"
        val files = mapOf(
            "/repo.json" to """{"name":"Local test repo","manifestVersion":1,"pluginLists":["$base/plugins.json"]}""".toByteArray(),
            "/plugins.json" to """[{"internalName":"TestPlugin","name":"Test plugin","version":2,"status":1,
                "jarUrl":"$base/TestPlugin.jar","jarHash":"$jarHash","jarFileSize":${jar.size}}]""".toByteArray(),
            "/TestPlugin.jar" to jar,
        )
        server.createContext("/") { exchange ->
            val body = files[exchange.requestURI.path]
            exchange.sendResponseHeaders(if (body == null) 404 else 200, body?.size?.toLong() ?: -1)
            body?.let { exchange.responseBody.write(it) }
            exchange.close()
        }
        server.start()
        return "$base/repo.json"
    }

    private fun sha256(bytes: ByteArray) =
        "sha256-" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun installFromRepositoryThenRestartThenUninstall() = runBlocking {
        val repoUrl = serveRepository(pluginJar(*pluginClasses))
        val client = RepositoryClient()
        val repo = client.fetchRepository(repoUrl)
        assertEquals("Local test repo", repo.name)
        val plugin = client.fetchPlugins(repo).single()

        val manager = DesktopPluginManager(dir.resolve("plugins"), client)
        manager.install(repoUrl, plugin)
        assertEquals(listOf("TestPlugin"), manager.installed.value.map { it.internalName })
        assertNotNull(manager.loadedPlugin("TestPlugin"))
        assertNotNull(APIHolder.getApiFromNameNull("Desktop Test Provider"))

        // A new manager on the same folder is what the next app start sees
        manager.loadedPlugin("TestPlugin")!!.unload()
        val restarted = DesktopPluginManager(dir.resolve("plugins"), client)
        assertEquals(2, restarted.installed.value.single().version)
        restarted.loadAll()
        assertNotNull(restarted.loadedPlugin("TestPlugin"))

        restarted.uninstall("TestPlugin")
        assertTrue(restarted.installed.value.isEmpty())
        assertFalse(dir.resolve("plugins/TestPlugin.jar").exists())
        assertTrue(APIHolder.apis.none { it.name == "Desktop Test Provider" })
    }

    @Test
    fun downloadWithTheWrongHashIsRefused() = runBlocking {
        val repoUrl = serveRepository(pluginJar(*pluginClasses), jarHash = "sha256-" + "0".repeat(64))
        val client = RepositoryClient()
        val plugin = client.fetchPlugins(client.fetchRepository(repoUrl)).single()
        val manager = DesktopPluginManager(dir.resolve("plugins"), client)

        assertFailsWith<PluginDownloadException> { manager.install(repoUrl, plugin) }
        assertTrue(manager.installed.value.isEmpty())
    }

    /**
     * Installs a real plugin from the official repository and searches with it.
     * Needs the internet, so it only runs with CLOUDSTREAM_ONLINE_TESTS=1.
     */
    @Test
    fun officialRepositoryPluginReturnsSearchResults() = runBlocking {
        if (System.getenv("CLOUDSTREAM_ONLINE_TESTS") != "1") return@runBlocking println("Skipped, set CLOUDSTREAM_ONLINE_TESTS=1")
        val client = RepositoryClient()
        val repo = client.fetchRepository(RepositoryClient.OFFICIAL_REPOSITORY)
        val plugin = client.fetchPlugins(repo).first { it.internalName == "InternetArchiveProvider" }

        val manager = DesktopPluginManager(dir.resolve("plugins"), client)
        manager.install(repo.url, plugin)
        val provider = manager.loadedPlugin(plugin.internalName)!!.providers.single()
        // The paged search is what the app calls, providers may implement only that one
        val results = provider.search("nosferatu", 1)?.items.orEmpty()
        println("${provider.name} returned ${results.size} results, first: ${results.firstOrNull()?.name}")
        assertTrue(results.isNotEmpty(), "Expected search results from ${provider.name}")
        manager.uninstall(plugin.internalName)
    }
}
