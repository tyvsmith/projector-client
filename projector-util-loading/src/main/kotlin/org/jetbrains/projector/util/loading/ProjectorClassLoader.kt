/*
 * MIT License
 *
 * Copyright (c) 2019-2021 JetBrains s.r.o.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.jetbrains.projector.util.loading

import org.jetbrains.projector.util.logging.Logger
import java.io.File
import java.io.InputStream
import java.lang.reflect.Method
import java.util.jar.JarFile

@Suppress("unused", "MemberVisibilityCanBePrivate")
public class ProjectorClassLoader constructor(parent: ClassLoader? = null) : ClassLoader(parent) {

  init {
    if (myInstance == null) myInstance = this
  }

  public var ideaClassLoader: ClassLoader? = null

  private val loadMethod = ClassLoader::class.java.getDeclaredMethod("loadClass", String::class.java, Boolean::class.java).apply(Method::unprotect)

  private val myAppClassLoader: ClassLoader by lazy {
    ClassLoader::class.java
      .getDeclaredMethod("getBuiltinAppClassLoader")
      .apply(Method::unprotect)
      .invoke(null) as ClassLoader
  }

  private val myIdeaLoader: ClassLoader get() = ideaClassLoader ?: myAppClassLoader

  private val forceLoadByPlatform = mutableSetOf<String>()

  private val jarFiles = mutableListOf<String>()

  public fun forceLoadByPlatform(className: String) {
    forceLoadByPlatform.add(className)
  }

  private fun mustBeLoadedByPlatform(name: String): Boolean = forceLoadByPlatform.any { name.startsWith(it) }

  private fun isProjectorAgentClass(name: String): Boolean = name.startsWith(PROJECTOR_AGENT_PACKAGE_PREFIX)
  private fun isProjectorClass(name: String): Boolean = name.startsWith(PROJECTOR_PACKAGE_PREFIX) && name != javaClass.name
  private fun isIntellijClass(name: String): Boolean = name.startsWith(INTELLIJ_PACKAGE_PREFIX) && !isProjectorClass(name)

  override fun loadClass(name: String, resolve: Boolean): Class<*> {
    synchronized(getClassLoadingLock(name)) {

      val found = findLoadedClass(name)
      if (found != null) return found

      return when {
        mustBeLoadedByPlatform(name) -> myAppClassLoader.loadClass(name, resolve)
        isProjectorClass(name) -> {

          if (isProjectorAgentClass(name)) {
            try {
              loadProjectorAgentClass(name, resolve)
            } catch (e: ClassNotFoundException) {
              loadProjectorClass(name, resolve)
            }
          } else {
            loadProjectorClass(name, resolve)
          }
        }
        isIntellijClass(name) -> myIdeaLoader.loadClass(name, resolve)
        else -> myAppClassLoader.loadClass(name, resolve)
      }
    }

  }

  private fun ClassLoader.loadClass(name: String, resolve: Boolean): Class<*> = loadMethod.invoke(this, name, resolve) as Class<*>

  private fun String.toClassFileName() = "${replace('.', '/')}.class"

  private fun loadProjectorClass(name: String, resolve: Boolean): Class<*> {
    val fileName = name.toClassFileName()
    return defineClass(name, myAppClassLoader.getResourceAsStream(fileName), resolve)
  }

  private fun loadProjectorAgentClass(name: String, resolve: Boolean): Class<*> {
    val fileName = name.toClassFileName()
    jarFiles.forEach { jarPath ->
      val file = File(jarPath)
      if (!file.exists()) return@forEach

      val jarFile = JarFile(file)
      val entry = jarFile.getJarEntry(fileName) ?: return@forEach

      return defineClass(name, jarFile.getInputStream(entry), resolve)
    }

    throw ClassNotFoundException(name)
  }

  private fun defineClass(name: String, inputStream: InputStream?, resolve: Boolean): Class<*> {
    val bytes = inputStream?.use { it.readAllBytes() } ?: throw ClassNotFoundException(name)

    val clazz = defineClass(name, bytes, 0, bytes.size)
    if (resolve) {
      resolveClass(clazz)
    }

    return clazz
  }

  override fun getResourceAsStream(name: String?): InputStream? {
    val resourceStream = listOf(myAppClassLoader, ideaClassLoader).fold(null as InputStream?) { stream, loader ->
      stream ?: loader?.getResourceAsStream(name)
    }
    if (resourceStream != null) return resourceStream

    return super.getResourceAsStream(name)
  }

  private fun appendToClassPathForInstrumentation(jarPath: String) {
    jarFiles += jarPath
  }

  public companion object {

    private const val INTELLIJ_PACKAGE_PREFIX = "com.intellij."
    private const val PROJECTOR_PACKAGE_PREFIX = "org.jetbrains.projector."
    private const val PROJECTOR_AGENT_PACKAGE_PREFIX = "org.jetbrains.projector.agent."

    private val logger = Logger<ProjectorClassLoader>()

    private var myInstance: ProjectorClassLoader? = null

    @JvmStatic
    public val instance: ProjectorClassLoader get() = myInstance ?: ProjectorClassLoader()
  }
}