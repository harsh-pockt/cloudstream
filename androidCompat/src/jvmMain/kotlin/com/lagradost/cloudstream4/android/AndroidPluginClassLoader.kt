package com.lagradost.cloudstream4.android

import java.net.URLClassLoader
import java.nio.file.Path
import java.security.CodeSource
import java.security.cert.Certificate
import java.util.jar.JarFile

/**
 * Loads an extension jar. When the extension refers to an Android class desktop has no version of,
 * such as a widget for its settings screen, an empty stub of that class is generated instead, with
 * the members the extension uses. The JVM only resolves classes when code using them runs, so the
 * parts of the extension that search and load links work, and stubbed parts do nothing.
 *
 * Calls to members a desktop version of an Android class lacks are redirected as the extension's
 * classes load, see MissingMembers.
 */
class AndroidPluginClassLoader(
    private val jar: Path,
    parent: ClassLoader,
    /** Stubs and redirects what desktop lacks, for Android plugins. Off for desktop jars, which fail plainly */
    private val stubMissing: Boolean = true,
) : URLClassLoader(arrayOf(jar.toUri().toURL()), parent) {
    /** Read from the jar the first time a class loads */
    private val model by lazy { StubModel.scan(jar) }
    private val generator by lazy { StubGenerator(model, parent) }
    private val missingMembers by lazy { MissingMembers(model, generator, parent) }
    private val codeSource = CodeSource(jar.toUri().toURL(), null as Array<Certificate>?)

    /** Classes are read from this rather than through URLs, whose cached jar files would keep the jar locked on Windows */
    private val jarFile = JarFile(jar.toFile())

    private val stubbed = mutableSetOf<String>()

    /** Names of the classes generated as stubs so far, to tell the user what the extension is missing */
    val stubbedClasses: Set<String> get() = synchronized(stubbed) { stubbed.toSet() }

    override fun findClass(name: String): Class<*> {
        if (stubMissing) missingMembers.helperClass(name)?.let { return define(name, it) }

        val internalName = name.replace('.', '/')
        val entry = synchronized(jarFile) { jarFile.getJarEntry("$internalName.class") }
        if (entry != null) {
            val bytes = synchronized(jarFile) { jarFile.getInputStream(entry).use { it.readBytes() } }
            return define(name, if (stubMissing) missingMembers.rewrite(name, bytes) else bytes)
        }

        if (!stubMissing || !StubHierarchy.isStubbed(internalName)) throw ClassNotFoundException(name)
        val bytes = generator.generate(internalName)
        synchronized(stubbed) { stubbed += name }
        return define(name, bytes)
    }

    override fun close() {
        try {
            super.close()
        } finally {
            synchronized(jarFile) { jarFile.close() }
        }
    }

    private fun define(name: String, bytes: ByteArray): Class<*> {
        val pkg = name.substringBeforeLast('.', "")
        if (pkg.isNotEmpty() && getDefinedPackage(pkg) == null) {
            runCatching { definePackage(pkg, null, null, null, null, null, null, null) }
        }
        return defineClass(name, bytes, 0, bytes.size, codeSource)
    }
}
