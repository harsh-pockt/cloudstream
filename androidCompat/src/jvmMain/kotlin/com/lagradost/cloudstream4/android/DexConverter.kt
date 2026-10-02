package com.lagradost.cloudstream4.android

import com.googlecode.d2j.Method
import com.googlecode.d2j.dex.Dex2jar
import com.googlecode.d2j.dex.DexExceptionHandler
import com.googlecode.d2j.node.DexCodeNode
import com.googlecode.d2j.node.DexFileNode
import com.googlecode.d2j.node.DexMethodNode
import com.googlecode.d2j.reader.BaseDexFileReader
import com.googlecode.d2j.reader.DexFileReader
import com.googlecode.d2j.reader.MultiDexFileReader
import com.googlecode.d2j.reader.Op
import com.googlecode.d2j.visitors.DexFileVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.lang.reflect.Modifier
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import kotlin.io.path.deleteIfExists

/** The result of converting an Android extension */
data class ConversionResult(
    /** Methods that could not be converted. They throw when called, the rest of the extension still works */
    val failedMethods: Int,
)

/**
 * Converts an Android extension (.cs3, a zip with classes.dex and manifest.json) into a jar the
 * desktop app can load, with dex2jar. The manifest is copied over, so the plugin class is found
 * the same way as in a desktop jar.
 */
object DexConverter {
    private const val MANIFEST = "manifest.json"

    /** What dex2jar reads with the options below: no debug info, names kept as they are */
    private const val READ_FLAGS = DexFileReader.SKIP_DEBUG or DexFileReader.DONT_SANITIZE_NAMES or DexFileReader.IGNORE_READ_EXCEPTION

    /**
     * Methods this big are not converted. dex2jar's memory use grows steeply with a method's size and
     * try blocks: StreamPlay's 26 000 instruction invokeMovieBox ran it out of 3 GB, while its next
     * biggest method, of 6 000, needs 250 MB. A method this big would also pass the JVM's 64 KB limit.
     */
    private const val MAX_INSTRUCTIONS = 12_000
    private const val MAX_COST = 3_000_000L

    fun convert(cs3: Path, jar: Path): ConversionResult {
        val failed = AtomicInteger()
        val handler = object : DexExceptionHandler {
            override fun handleFileException(e: Exception) {
                throw IllegalArgumentException("Not a readable Android extension: ${e.message ?: e}", e)
            }

            /** Like the dex2jar command, the method gets a body that throws instead */
            override fun handleMethodTranslateException(method: Method, node: DexMethodNode, mv: MethodVisitor, e: Exception) {
                failed.incrementAndGet()
                println("WARNING DexConverter: Could not convert $method in $cs3: $e")
                mv.visitTypeInsn(Opcodes.NEW, "java/lang/RuntimeException")
                mv.visitInsn(Opcodes.DUP)
                mv.visitLdcInsn("This method of the extension could not be converted for desktop: $e")
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/RuntimeException", "<init>", "(Ljava/lang/String;)V", false)
                mv.visitInsn(Opcodes.ATHROW)
            }
        }

        val dex = DexFileNode()
        MultiDexFileReader.open(Files.readAllBytes(cs3)).accept(dex, READ_FLAGS)
        failed.addAndGet(replaceHugeMethods(dex, cs3))

        val tmp = jar.resolveSibling("${jar.fileName}.tmp")
        tmp.deleteIfExists()
        try {
            // The same options as the dex2jar command's defaults, except that names are kept:
            // Kotlin names such as "constructor-impl" are valid on the JVM, and renaming them breaks the calls
            Dex2jar.from(NodeReader(dex))
                .withExceptionHandler(handler)
                .reUseReg(false)
                .topoLogicalSort()
                .skipDebug(true)
                .dontSanitizeNames(true)
                .optimizeSynchronized(false)
                .printIR(false)
                .noCode(false)
                .skipExceptions(false)
                .to(tmp)
            copyManifest(cs3, tmp)
            Files.move(tmp, jar, REPLACE_EXISTING, ATOMIC_MOVE)
        } finally {
            tmp.deleteIfExists()
        }
        return ConversionResult(failed.get())
    }

    /** Gives methods too big to convert a body that throws. Returns how many there were */
    private fun replaceHugeMethods(dex: DexFileNode, cs3: Path): Int {
        var replaced = 0
        for (cls in dex.clzs.orEmpty()) for (method in cls.methods.orEmpty()) {
            val code = method.codeNode ?: continue
            val instructions = code.stmts.size
            val cost = instructions.toLong() * (code.totalRegister + (code.tryStmts?.size ?: 0))
            if (instructions <= MAX_INSTRUCTIONS && cost <= MAX_COST) continue
            println("WARNING DexConverter: ${method.method} in $cs3 is too big to convert ($instructions instructions)")
            method.codeNode = throwingCode(method)
            replaced++
        }
        return replaced
    }

    /** throw new RuntimeException(message), with the method's parameters in the last registers like dex expects */
    private fun throwingCode(method: DexMethodNode): DexCodeNode {
        val parameterRegisters = method.method.parameterTypes.sumOf { if (it == "J" || it == "D") 2 else 1 } +
            if (method.access and Modifier.STATIC != 0) 0 else 1
        return DexCodeNode().apply {
            visitRegister(2 + parameterRegisters)
            visitTypeStmt(Op.NEW_INSTANCE, 0, 0, "Ljava/lang/RuntimeException;")
            visitConstStmt(Op.CONST_STRING, 1, "This method of the extension is too big to convert for desktop")
            visitMethodStmt(Op.INVOKE_DIRECT, intArrayOf(0, 1), Method("Ljava/lang/RuntimeException;", "<init>", arrayOf("Ljava/lang/String;"), "V"))
            visitStmt1R(Op.THROW, 0)
        }
    }

    /** Hands dex2jar the dex that was read and edited above */
    private class NodeReader(private val dex: DexFileNode) : BaseDexFileReader {
        override fun getDexVersion(): Int = dex.dexVersion
        override fun getClassNames(): List<String> = dex.clzs.map { it.className }
        override fun accept(dv: DexFileVisitor) = dex.accept(dv)
        override fun accept(dv: DexFileVisitor, config: Int) = dex.accept(dv)
        override fun accept(dv: DexFileVisitor, classIdx: Int, config: Int) = dex.clzs[classIdx].accept(dv)
    }

    private fun copyManifest(cs3: Path, jar: Path) {
        val manifest = ZipFile(cs3.toFile()).use { zip ->
            zip.getEntry(MANIFEST)?.let { entry -> zip.getInputStream(entry).use { it.readBytes() } }
        } ?: return
        FileSystems.newFileSystem(URI.create("jar:" + jar.toUri()), emptyMap<String, Any>()).use { fs ->
            Files.write(fs.getPath(MANIFEST), manifest)
        }
    }
}
