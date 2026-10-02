package com.lagradost.cloudstream4.android

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.nio.file.Path
import java.util.jar.JarFile

/** What an extension uses of each class desktop has to generate a stub for */
internal class StubModel private constructor() {
    class Member(val name: String, val desc: String, val isStatic: Boolean)

    class TypeUse {
        /** Called with invokeinterface or implemented by an extension class */
        var usedAsInterface = false

        /** Created with new or extended by an extension class */
        var usedAsClass = false
        val methods = LinkedHashMap<String, Member>()
        val fields = LinkedHashMap<String, Member>()

        val isInterface get() = usedAsInterface && !usedAsClass
    }

    private class ExtensionClass(val superName: String?, val declared: MutableSet<String> = mutableSetOf())

    /** A use of a member through an extension class, which may come from a stubbed superclass */
    private class InheritedUse(val owner: String, val member: Member, val isField: Boolean)

    private val extensionClasses = HashMap<String, ExtensionClass>()
    private val inheritedUses = mutableListOf<InheritedUse>()
    val types = HashMap<String, TypeUse>()

    private fun use(name: String) = types.getOrPut(name) { TypeUse() }

    private fun record(owner: String, member: Member, isField: Boolean) {
        when {
            owner.startsWith("[") -> {}
            StubHierarchy.isStubbed(owner) -> {
                val use = use(owner)
                (if (isField) use.fields else use.methods).putIfAbsent(member.name + member.desc, member)
            }
            else -> inheritedUses += InheritedUse(owner, member, isField)
        }
    }

    private fun scanClass(bytes: ByteArray) {
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            lateinit var current: ExtensionClass

            override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<String>?) {
                current = ExtensionClass(superName).also { extensionClasses[name] = it }
                if (superName != null && StubHierarchy.isStubbed(superName)) use(superName).usedAsClass = true
                interfaces?.filter(StubHierarchy::isStubbed)?.forEach { use(it).usedAsInterface = true }
            }

            override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?) =
                null.also { current.declared += name + descriptor }

            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<String>?): MethodVisitor {
                current.declared += name + descriptor
                return object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                        if (isInterface && StubHierarchy.isStubbed(owner)) use(owner).usedAsInterface = true
                        record(owner, Member(name, descriptor, opcode == Opcodes.INVOKESTATIC), isField = false)
                    }

                    override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
                        val isStatic = opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC
                        record(owner, Member(name, descriptor, isStatic), isField = true)
                    }

                    override fun visitTypeInsn(opcode: Int, type: String) {
                        if (opcode == Opcodes.NEW && StubHierarchy.isStubbed(type)) use(type).usedAsClass = true
                    }
                }
            }
        }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
    }

    /**
     * Where a member used through [owner] comes from: [owner] itself when it is not an extension
     * class, else its first ancestor outside the extension. Null when the extension declares it.
     */
    fun firstAncestorOutside(owner: String, key: String): String? {
        var current: String? = owner
        while (current != null) {
            val cls = extensionClasses[current] ?: return current
            if (key in cls.declared) return null
            current = cls.superName
        }
        return null
    }

    /** Members used through an extension class but declared nowhere in the extension belong to its stubbed ancestor */
    private fun resolveInheritedUses() {
        for (use in inheritedUses) {
            val key = use.member.name + use.member.desc
            var current: String? = use.owner
            while (current != null) {
                val cls = extensionClasses[current] ?: break
                if (key in cls.declared) break
                current = cls.superName
            }
            if (current != null && extensionClasses[current] == null && StubHierarchy.isStubbed(current)) {
                val type = use(current)
                (if (use.isField) type.fields else type.methods).putIfAbsent(key, use.member)
            }
        }
        inheritedUses.clear()
    }

    companion object {
        fun scan(jar: Path): StubModel = StubModel().apply {
            JarFile(jar.toFile()).use { file ->
                for (entry in file.entries()) {
                    if (!entry.name.endsWith(".class")) continue
                    runCatching { scanClass(file.getInputStream(entry).use { it.readBytes() }) }
                        .onFailure { println("WARNING StubModel: Could not read ${entry.name} in $jar: $it") }
                }
            }
            resolveInheritedUses()
        }
    }
}

/**
 * Writes the stub classes. A stub has the members the extension uses: constructors that do nothing,
 * and methods that return a default value. Where the value is an object the stub can create, such
 * as Toast.makeText's Toast, it returns a new one so chained calls keep doing nothing rather than
 * failing on null. Members a real desktop class above the stub already has are left out, so the
 * stub never hides them.
 */
internal class StubGenerator(private val model: StubModel, private val parent: ClassLoader) {
    private val parentClasses = HashMap<String, Class<*>?>()

    private fun parentClass(internalName: String): Class<*>? = parentClasses.getOrPut(internalName) {
        try {
            Class.forName(internalName.replace('/', '.'), false, parent)
        } catch (_: ClassNotFoundException) {
            null
        } catch (_: LinkageError) {
            null
        }
    }

    private fun isInterface(internalName: String): Boolean =
        parentClass(internalName)?.isInterface ?: (model.types[internalName]?.isInterface == true)

    /** The first real class above a stub, where the stub's own ancestors end */
    private fun firstRealAncestor(superName: String): Class<*>? {
        var current = superName
        repeat(32) {
            parentClass(current)?.let { return it }
            if (!StubHierarchy.isStubbed(current)) return null
            current = StubHierarchy.superclassOf(current)
        }
        return null
    }

    fun hasMethod(cls: Class<*>, name: String, desc: String): Boolean {
        val seen = HashSet<Class<*>>()
        val queue = ArrayDeque(listOf(cls))
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (!seen.add(c)) continue
            if (c.declaredMethods.any { it.name == name && Type.getMethodDescriptor(it) == desc }) return true
            c.superclass?.let(queue::add)
            queue.addAll(c.interfaces)
        }
        return false
    }

    fun hasField(cls: Class<*>, name: String, desc: String): Boolean {
        var c: Class<*>? = cls
        while (c != null) {
            if (c.declaredFields.any { it.name == name && Type.getDescriptor(it.type) == desc }) return true
            if (c.interfaces.any { i -> i.fields.any { it.name == name } }) return true
            c = c.superclass
        }
        return false
    }

    fun generate(internalName: String): ByteArray {
        val use = model.types[internalName] ?: StubModel.TypeUse()
        val isInterface = use.isInterface
        val superName = if (isInterface) "java/lang/Object" else StubHierarchy.superclassOf(internalName)
        val realAncestor = firstRealAncestor(superName) ?: Any::class.java

        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        val access = Opcodes.ACC_PUBLIC or if (isInterface) Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT else Opcodes.ACC_SUPER
        writer.visit(Opcodes.V1_8, access, internalName, null, superName, null)

        // Static object fields get an instance when the stub can make one, such as a Kotlin Companion or INSTANCE
        val initialized = mutableListOf<StubModel.Member>()
        for (field in use.fields.values) {
            if (hasField(realAncestor, field.name, field.desc)) continue
            val isStatic = isInterface || field.isStatic
            val fieldAccess = Opcodes.ACC_PUBLIC or when {
                isInterface -> Opcodes.ACC_STATIC or Opcodes.ACC_FINAL
                field.isStatic -> Opcodes.ACC_STATIC
                else -> 0
            }
            writer.visitField(fieldAccess, field.name, field.desc, null, null).visitEnd()
            val type = Type.getType(field.desc)
            if (isStatic && type.sort == Type.OBJECT && canCreate(type.internalName)) initialized += field
        }
        if (initialized.isNotEmpty()) {
            writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null).apply {
                visitCode()
                for (field in initialized) {
                    newInstance(this, Type.getType(field.desc).internalName)
                    visitFieldInsn(Opcodes.PUTSTATIC, internalName, field.name, field.desc)
                }
                visitInsn(Opcodes.RETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
        }

        if (!isInterface) {
            val constructors = use.methods.values.filter { it.name == "<init>" }.map { it.desc }.toMutableSet()
            constructors += "()V"
            for (desc in constructors) {
                writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", desc, null, null).apply {
                    visitCode()
                    visitVarInsn(Opcodes.ALOAD, 0)
                    visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false)
                    visitInsn(Opcodes.RETURN)
                    visitMaxs(0, 0)
                    visitEnd()
                }
            }
        }

        val written = HashSet<String>()
        for (method in use.methods.values) {
            if (method.name.startsWith("<")) continue
            // A static and an instance method cannot share a name and descriptor, keep the first use
            if (!written.add(method.name + method.desc)) continue
            if (!method.isStatic && hasMethod(if (isInterface) Any::class.java else realAncestor, method.name, method.desc)) continue
            if (method.isStatic && !isInterface && hasMethod(realAncestor, method.name, method.desc)) continue
            val methodAccess = Opcodes.ACC_PUBLIC or if (method.isStatic) Opcodes.ACC_STATIC else 0
            writer.visitMethod(methodAccess, method.name, method.desc, null, null).apply {
                visitCode()
                returnDefault(this, Type.getReturnType(method.desc))
                visitMaxs(0, 0)
                visitEnd()
            }
        }

        writer.visitEnd()
        return writer.toByteArray()
    }

    /** True when a stub can return a new instance of the type */
    private fun canCreate(internalName: String): Boolean {
        val real = parentClass(internalName)
        if (real != null) {
            val modifiers = real.modifiers
            return !real.isInterface && !java.lang.reflect.Modifier.isAbstract(modifiers) &&
                java.lang.reflect.Modifier.isPublic(modifiers) &&
                real.constructors.any { it.parameterCount == 0 } &&
                StubHierarchy.isStubbed(internalName)
        }
        return StubHierarchy.isStubbed(internalName) && !isInterface(internalName)
    }

    fun returnDefault(mv: MethodVisitor, type: Type) {
        when (type.sort) {
            Type.VOID -> mv.visitInsn(Opcodes.RETURN)
            Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> {
                mv.visitInsn(Opcodes.ICONST_0)
                mv.visitInsn(Opcodes.IRETURN)
            }
            Type.LONG -> {
                mv.visitInsn(Opcodes.LCONST_0)
                mv.visitInsn(Opcodes.LRETURN)
            }
            Type.FLOAT -> {
                mv.visitInsn(Opcodes.FCONST_0)
                mv.visitInsn(Opcodes.FRETURN)
            }
            Type.DOUBLE -> {
                mv.visitInsn(Opcodes.DCONST_0)
                mv.visitInsn(Opcodes.DRETURN)
            }
            Type.ARRAY -> {
                mv.visitInsn(Opcodes.ICONST_0)
                val element = Type.getType(type.descriptor.substring(1))
                when (element.sort) {
                    Type.OBJECT, Type.ARRAY -> mv.visitTypeInsn(Opcodes.ANEWARRAY, element.internalName)
                    else -> mv.visitIntInsn(Opcodes.NEWARRAY, newArrayCode(element))
                }
                mv.visitInsn(Opcodes.ARETURN)
            }
            else -> {
                val name = type.internalName
                val created = when (name) {
                    "java/lang/String", "java/lang/CharSequence" -> {
                        mv.visitLdcInsn("")
                        true
                    }
                    "java/util/List", "java/util/Collection", "java/lang/Iterable", "java/util/ArrayList" -> newInstance(mv, "java/util/ArrayList")
                    "java/util/Set", "java/util/HashSet" -> newInstance(mv, "java/util/HashSet")
                    "java/util/Map", "java/util/HashMap" -> newInstance(mv, "java/util/HashMap")
                    // Such as View.getContext(), the desktop activity is every context there is
                    "android/content/Context", "android/content/ContextWrapper", "android/view/ContextThemeWrapper", "android/app/Activity" -> {
                        val desktop = Type.getInternalName(DesktopAndroid::class.java)
                        mv.visitFieldInsn(Opcodes.GETSTATIC, desktop, "INSTANCE", "L$desktop;")
                        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, desktop, "getActivity", "()Landroid/app/Activity;", false)
                        true
                    }
                    else -> if (canCreate(name)) newInstance(mv, name) else false
                }
                if (!created) mv.visitInsn(Opcodes.ACONST_NULL)
                mv.visitInsn(Opcodes.ARETURN)
            }
        }
    }

    private fun newInstance(mv: MethodVisitor, internalName: String): Boolean {
        mv.visitTypeInsn(Opcodes.NEW, internalName)
        mv.visitInsn(Opcodes.DUP)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, internalName, "<init>", "()V", false)
        return true
    }

    private fun newArrayCode(element: Type) = when (element.sort) {
        Type.BOOLEAN -> Opcodes.T_BOOLEAN
        Type.CHAR -> Opcodes.T_CHAR
        Type.BYTE -> Opcodes.T_BYTE
        Type.SHORT -> Opcodes.T_SHORT
        Type.INT -> Opcodes.T_INT
        Type.LONG -> Opcodes.T_LONG
        Type.FLOAT -> Opcodes.T_FLOAT
        else -> Opcodes.T_DOUBLE
    }
}
