package com.spark.wizard

import com.spark.Constants
import org.apache.commons.io.IOUtils
import org.objectweb.asm.*
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

/**
 * @author yun.
 * @date 2021/7/21
 * @des [一句话描述]
 * @since [https://github.com/ZuYun]
 * <p><a href="https://github.com/ZuYun">github</a>
 */

const val loadRouterMap = "loadRouterMap"
const val arouterFilePrefix = "ARouter$$"
const val logisticsCenterClass = "LogisticsCenter.class"
private val routesClassNames = mutableListOf<String>()


class ArouterMagic : IWizard() {

    companion object {
        var arouterApiJarPath = "LogisticsCenter.class"
    }

    val regex = Regex("/|\\\\")

    override fun transformStart() {
        routesClassNames.clear()
    }

    override fun checkIfJarEntryMatches(srcJarEntry: JarEntry, srcJarFile: File, destJarFile: File): Boolean {
        return false
    }


    override fun checkIfJarMatches(srcJarFile: File, destJarFile: File): Boolean {
        if (srcJarFile.name.contains("arouter-api")) {
            println(" ... $this >>  checkIfJarMatches ${srcJarFile.name}")
            holdArouterApiJarPath(destJarFile.path)
            JarFile(srcJarFile).use { jarFile ->
                jarFile.entries().toList().onEach {
                    if (it.name.contains(arouterFilePrefix)) {
                        keepRouterClassName(it.name)
                    }
                }
            }
        } else if (srcJarFile.name.equals(Constants.moduleClassName)) {
            println(" ... $this >>  checkIfJarMatches ${srcJarFile.name}")
            val jarFile = JarFile(srcJarFile)
            jarFile.use {
                it.entries().toList().onEach { entry ->
                    println(".. in jar ${srcJarFile.name} >>>>>>>>>  ${entry.name} ")
                    if (entry.name.contains(arouterFilePrefix)) {
                        keepRouterClassName(entry.name)
                    }
                }
            }
        }
        return false
    }


    override fun checkIfFileMatches(srcFile: File, destFile: File): Boolean {
        if (srcFile.name.startsWith(arouterFilePrefix)) {
            keepRouterClassName(srcFile.path)
        }
        return false
    }


    override fun transformEnd() {
        println(" ... $this >> transformEnd ")
        //修改jar
        if (arouterApiJarPath.endsWith(".class")) {
            println(" ... $this >>  error")
            return
        }
        if (routesClassNames.isNotEmpty()) {
            reWriteLogisticsCenterClass()
            routesClassNames.clear()
        }
    }

    private fun holdArouterApiJarPath(path: String) {
        arouterApiJarPath = path
    }

    private fun keepRouterClassName(path: String) {
        val className = path.substring(path.indexOf("com"), path.indexOf(".class")).replace(regex, ".")
        routesClassNames.add(className)
    }


    private fun reWriteLogisticsCenterClass() {
        val temfile = File("$arouterApiJarPath.temp")
        if (temfile.exists()) {
            temfile.delete()
        }
        val newjarOutputStream = JarOutputStream(FileOutputStream(temfile))
        val originFile = File(arouterApiJarPath)
        newjarOutputStream.use {
            val jarFile = JarFile(originFile)
            jarFile.entries().toList().onEach {
                newjarOutputStream.putNextEntry(ZipEntry(it.name))
                val inputStream = jarFile.getInputStream(it)
                if (it.name.endsWith(logisticsCenterClass)) {
                    println("fond logisticsCenterClass -->> ${it.name}")
                    //插入代码
                    val newBye = logisticsCenterVisitor(inputStream)
                    newjarOutputStream.write(newBye)
                } else {
                    newjarOutputStream.write(IOUtils.toByteArray(inputStream))
                }
                inputStream.close()
                newjarOutputStream.closeEntry()
            }
            jarFile.close()
        }
        originFile.delete()
        println("${temfile.path}  ${temfile.renameTo(originFile)}")
    }


    private fun logisticsCenterVisitor(inputStream: InputStream): ByteArray {
        val classReader = ClassReader(inputStream)
        val classWriter = ClassWriter(classReader, ClassWriter.COMPUTE_MAXS)
        println(" ... $this >> loadRouterMap ${routesClassNames.size} \n $routesClassNames")
        classReader.accept(object : ClassVisitor(Opcodes.ASM5, classWriter) {

            override fun visit(
                version: Int,
                access: Int,
                name: String?,
                signature: String?,
                superName: String?,
                interfaces: Array<out String>?
            ) {
                super.visit(version, access, name, signature, superName, interfaces)
            }

            override fun visitMethod(
                access: Int,
                name: String?,
                descriptor: String?,
                signature: String?,
                exceptions: Array<out String>?
            ): MethodVisitor {
                val visitMethod = super.visitMethod(access, name, descriptor, signature, exceptions)
                if (loadRouterMap == name) {
                    println(" ... $this >>  visitMethod $name ======= ")
                    return LoadRouterMethodVisitor(methodVisitor = visitMethod)
                }
                return visitMethod
            }

        }, ClassReader.SKIP_DEBUG)
        return classWriter.toByteArray()
    }

}

class LoadRouterMethodVisitor(api: Int = Opcodes.ASM5, methodVisitor: MethodVisitor) : MethodVisitor(api, methodVisitor) {

    val logisitscCenter = "com/alibaba/android/arouter/core/LogisticsCenter"

    override fun visitInsn(opcode: Int) {
        if (opcode == Opcodes.RETURN) {
            mv.visitLdcInsn("TAG")
            mv.visitLdcInsn("$logisitscCenter --> visitInsn")
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "android/util/Log", "i", "(Ljava/lang/String;Ljava/lang/String;)I", false)
            mv.visitInsn(Opcodes.POP)
            println(" ... $this >>  visitInsn $opcode  $routesClassNames")
            routesClassNames.onEach {
                mv.visitLdcInsn(it)
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, logisitscCenter, "register", "(Ljava/lang/String;)V", false)
            }
        }
        super.visitInsn(opcode)
    }

}
