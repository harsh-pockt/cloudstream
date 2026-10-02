package com.lagradost.cloudstream4.android

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * The desktop versions of Android classes, such as Context, only have the members extensions
 * commonly use. When an extension class uses one they lack, such as Context.getContentResolver(),
 * the call is redirected while the class loads to a generated helper that returns a default value,
 * the same thing a stub does. Without this the call would fail with NoSuchMethodError.
 *
 * Only the desktop versions in this module are treated this way, never the library or the JDK,
 * where a missing member is a real error.
 */
internal class MissingMembers(
    private val model: StubModel,
    private val generator: StubGenerator,
    private val parent: ClassLoader,
) {
    private val standInLocation = MissingMembers::class.java.protectionDomain?.codeSource?.location

    private class Helper(val name: String, val desc: String)

    /** Helper classes waiting to be defined, by binary class name */
    private val helpers = HashMap<String, List<Helper>>()

    /** The desktop version a member is missing from, or null when it exists or is not ours to fill in */
    private val missing = HashMap<String, Class<*>?>()

    private fun parentClass(internalName: String): Class<*>? = try {
        Class.forName(internalName.replace('/', '.'), false, parent)
    } catch (_: ClassNotFoundException) {
        null
    } catch (_: LinkageError) {
        null
    }

    private fun isStandIn(cls: Class<*>) = StubHierarchy.isStubbed(Type.getInternalName(cls)) &&
        standInLocation != null && cls.protectionDomain?.codeSource?.location == standInLocation

    private fun missingFrom(owner: String, name: String, desc: String, isField: Boolean): Class<*>? =
        synchronized(missing) {
            missing.getOrPut("$owner.$name$desc$isField") {
                if (owner.startsWith("[")) return@getOrPut null
                // Members used through an extension class come from its first ancestor outside the extension
                val ancestor = model.firstAncestorOutside(owner, name + desc) ?: return@getOrPut null
                if (name == "<init>" && ancestor != owner) return@getOrPut null
                val cls = parentClass(ancestor)?.takeIf(::isStandIn) ?: return@getOrPut null
                val exists = when {
                    isField -> generator.hasField(cls, name, desc)
                    name == "<init>" -> cls.declaredConstructors.any { Type.getConstructorDescriptor(it) == desc }
                    else -> generator.hasMethod(cls, name, desc)
                }
                if (exists) null else cls
            }
        }

    /** Returns the class with calls to missing members redirected, or the same bytes when there are none */
    fun rewrite(binaryName: String, bytes: ByteArray): ByteArray {
        val reader = ClassReader(bytes)
        val writer = ClassWriter(reader, 0)
        val helperName = binaryName.replace('.', '/') + "\$\$DesktopMissing"
        val added = mutableListOf<Helper>()

        fun helper(desc: String): Helper = Helper("missing${added.size}", desc).also { added += it }

        reader.accept(object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<String>?): MethodVisitor =
                object : MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                    override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, isInterface: Boolean) {
                        val cls = missingFrom(owner, name, desc, isField = false)
                        when {
                            cls == null -> super.visitMethodInsn(opcode, owner, name, desc, isInterface)
                            name == "<init>" -> {
                                // The arguments are used up by the helper and the object made with the constructor that exists
                                if (cls.declaredConstructors.none { it.parameterCount == 0 }) {
                                    super.visitMethodInsn(opcode, owner, name, desc, isInterface)
                                    return
                                }
                                val h = helper(desc)
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, helperName, h.name, h.desc, false)
                                super.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "<init>", "()V", false)
                            }
                            else -> {
                                val h = helper(if (opcode == Opcodes.INVOKESTATIC) desc else "(L$owner;" + desc.substring(1))
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, helperName, h.name, h.desc, false)
                            }
                        }
                    }

                    override fun visitFieldInsn(opcode: Int, owner: String, name: String, desc: String) {
                        if (missingFrom(owner, name, desc, isField = true) == null) return super.visitFieldInsn(opcode, owner, name, desc)
                        val h = helper(
                            when (opcode) {
                                Opcodes.GETSTATIC -> "()$desc"
                                Opcodes.GETFIELD -> "(L$owner;)$desc"
                                Opcodes.PUTSTATIC -> "($desc)V"
                                else -> "(L$owner;$desc)V"
                            }
                        )
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, helperName, h.name, h.desc, false)
                    }
                }
        }, 0)

        if (added.isEmpty()) return bytes
        synchronized(helpers) { helpers[helperName.replace('/', '.')] = added }
        return writer.toByteArray()
    }

    /** The helper class for an extension class rewritten above, or null when the name is not one */
    fun helperClass(binaryName: String): ByteArray? {
        val list = synchronized(helpers) { helpers.remove(binaryName) } ?: return null
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER, binaryName.replace('.', '/'), null, "java/lang/Object", null)
        for (helper in list) {
            writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, helper.name, helper.desc, null, null).apply {
                visitCode()
                generator.returnDefault(this, Type.getReturnType(helper.desc))
                visitMaxs(0, 0)
                visitEnd()
            }
        }
        writer.visitEnd()
        return writer.toByteArray()
    }
}
