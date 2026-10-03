package com.lagradost.cloudstream4.plugins

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.plugins.PluginManager
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.io.path.readBytes
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

    @Test
    fun aPluginNameThatIsNotASafeFileNameIsSkipped() {
        val names = listOf("../../evil", "C:/Users/Public/evil", "..", "a b", "ok.Name_1-2")
        val plugins = RepositoryClient.parsePluginList(
            names.joinToString(",", "[", "]") { """{"internalName":"$it","name":"x","version":1,"jarUrl":"https://h/x.jar"}""" }
        )
        assertEquals(listOf("ok.Name_1-2"), plugins.map { it.internalName })
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
            "/Broken.cs3" to "not an Android extension".toByteArray(),
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
        // Like Android, extensions see the file they came from, kept across restarts
        assertEquals(listOf("${repoUrl.removeSuffix("repo.json")}TestPlugin.jar"), PluginManager.getPluginsOnline().map { it.url })
        restarted.loadAll()
        assertNotNull(restarted.loadedPlugin("TestPlugin"))

        restarted.uninstall("TestPlugin")
        assertTrue(restarted.installed.value.isEmpty())
        assertFalse(dir.resolve("plugins/TestPlugin.jar").exists())
        assertTrue(APIHolder.apis.none { it.name == "Desktop Test Provider" })
    }

    @Test
    fun anUpdateThatFailsToConvertKeepsTheOldVersionRunning() = runBlocking {
        val repoUrl = serveRepository(pluginJar(*pluginClasses))
        val client = RepositoryClient()
        val plugin = client.fetchPlugins(client.fetchRepository(repoUrl)).single()
        val manager = DesktopPluginManager(dir.resolve("plugins"), client)
        manager.install(repoUrl, plugin)
        val jar = dir.resolve("plugins/TestPlugin.jar")
        val before = jar.readBytes()

        // Version 3 is only an Android build, and not a readable one
        val broken = plugin.copy(version = 3, jarUrl = null, jarHash = null, jarFileSize = null, androidUrl = repoUrl.replace("repo.json", "Broken.cs3"))
        assertFailsWith<PluginDownloadException> { manager.install(repoUrl, broken) }
        assertNotNull(manager.loadedPlugin("TestPlugin"), "the old version is still loaded")
        assertNotNull(APIHolder.getApiFromNameNull("Desktop Test Provider"))
        assertEquals(2, manager.installed.value.single().version)
        assertTrue(before.contentEquals(jar.readBytes()))
        assertTrue(Files.list(dir.resolve("plugins")).use { files -> files.noneMatch { it.fileName.toString().endsWith(".new") } })
        manager.uninstall("TestPlugin")
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

    /** A short link site: known codes redirect to a repository, unknown ones to its 404 page */
    private fun serveShortLinks(): String {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { this.server = it }
        val base = "http://127.0.0.1:${server.address.port}"
        server.createContext("/") { exchange ->
            val location = when (exchange.requestURI.path) {
                "/megarepo" -> "https://raw.githubusercontent.com/self-similarity/MegaRepo/builds/repo.json"
                "/home" -> "$base/"
                else -> "$base/404"
            }
            exchange.responseHeaders.add("Location", location)
            exchange.sendResponseHeaders(301, -1)
            exchange.close()
        }
        server.start()
        return "$base/"
    }

    @Test
    fun repositoryShortCodesAndLinksResolveLikeAndroid() = runBlocking {
        val shortLinks = serveShortLinks()
        val client = RepositoryClient(shortLinkHost = shortLinks, bangShortLinkHost = shortLinks)

        assertEquals("https://raw.githubusercontent.com/self-similarity/MegaRepo/builds/repo.json", client.resolveRepositoryUrl(" megarepo "))
        assertEquals("https://raw.githubusercontent.com/self-similarity/MegaRepo/builds/repo.json", client.resolveRepositoryUrl("!megarepo"))
        assertEquals(null, client.resolveRepositoryUrl("unknown-code"))
        assertEquals(null, client.resolveRepositoryUrl("home"))
        assertEquals(null, client.resolveRepositoryUrl("not a code"))

        assertEquals("https://example.com/repo.json", client.resolveRepositoryUrl("https://example.com/repo.json"))
        assertEquals("https://example.com/repo.json", client.resolveRepositoryUrl("cloudstreamrepo://example.com/repo.json"))
        assertEquals("https://example.com/repo.json", client.resolveRepositoryUrl("https://cs.repo/?https://example.com/repo.json"))
        assertEquals("https://example.com/repo.json", client.resolveRepositoryUrl("https://cs.repo/example.com/repo.json"))
    }

    @Test
    fun communityRepositoryListTakesBothEntryForms() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { this@PluginTest.server = it }
        val body = """["https://a/repo.json", {"url":"https://b/repo.json","verified":true}, {"name":"no url"}, "https://a/repo.json"]""".toByteArray()
        server.createContext("/repos-db.json") { it.sendResponseHeaders(200, body.size.toLong()); it.responseBody.write(body); it.close() }
        server.start()

        val repos = RepositoryClient().fetchCommunityRepositories("http://127.0.0.1:${server.address.port}/repos-db.json")
        assertEquals(listOf("https://a/repo.json", "https://b/repo.json"), repos)
    }

    /**
     * Resolves the real megarepo short code and reads the real community list.
     * Needs the internet, so it only runs with CLOUDSTREAM_ONLINE_TESTS=1.
     */
    @Test
    fun megarepoShortCodeAndCommunityListOnline() = runBlocking {
        if (System.getenv("CLOUDSTREAM_ONLINE_TESTS") != "1") return@runBlocking println("Skipped, set CLOUDSTREAM_ONLINE_TESTS=1")
        val client = RepositoryClient()
        val url = client.resolveRepositoryUrl("megarepo")
        println("megarepo -> $url")
        val plugins = client.fetchPlugins(client.fetchRepository(url!!))
        assertEquals(listOf(RepositoryClient.MEGA_REPO_PLUGIN), plugins.map { it.internalName })
        val community = client.fetchCommunityRepositories()
        println("${community.size} community repositories")
        assertTrue(community.size > 10)
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
