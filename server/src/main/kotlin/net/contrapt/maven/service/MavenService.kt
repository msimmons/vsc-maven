package net.contrapt.maven.service

import net.contrapt.maven.model.ClasspathData
import net.contrapt.maven.model.CompileMessage
import net.contrapt.maven.model.CompileResult
import net.contrapt.maven.model.DependencyData
import org.apache.maven.cli.MavenConnector
import org.apache.maven.execution.MavenExecutionResult
import org.apache.maven.plugin.MojoFailureException
import java.io.File

/**
 *
 */
class MavenService(val projectDir: String, val extensionDir: String) {

    private val ERROR_PATTERN = "^(.*):\\[(\\d+),(\\d+)\\]\\s+(.*)\$".toRegex()
    val connector = MavenConnector(projectDir)
    lateinit var result : MavenExecutionResult
        private set

    /**
     * Refresh the project model
     */
    fun refresh() {
        result = connector.connect()
        if (result.hasExceptions()) throw result.exceptions.first()
    }

    fun getTasks() : Collection<String> {
        val tasks = mutableSetOf<String>()
        result.topologicallySortedProjects.forEach { project ->
            project.model.build.plugins.forEach { plugin ->
                plugin.executions.forEach { ex ->
                    if (ex.phase != null) tasks.add(ex.phase)
                    ex.goals.forEach { g ->
                        tasks.add(g)
                    }
                }
            }
        }
        return tasks.sorted()
    }

    /**
     * Get dependencies
     */
    fun getDependencies() : Collection<DependencyData> {
        val deps = mutableMapOf<String, DependencyData>()
        result.topologicallySortedProjects.forEach { module ->
            module.artifacts.forEach { art ->
                val key = "${art.groupId}:${art.artifactId}:${art.version}:${art.classifier}"
                if (art.file?.exists() ?: false) {
                    val dep = deps.getOrPut(key, { DependencyData(art.file.absolutePath, null, art.groupId, art.artifactId, art.version) })
                    dep.transitive = art.dependencyTrail.size > 2
                    dep.modules.add(module.name)
                    dep.scopes.add(art.scope)
                    val srcFile = File(art.file.absolutePath.replace(".jar", "-sources.jar"))
                    if (srcFile.exists()) dep.sourceFileName = srcFile.absolutePath
                }
            }
        }
        return deps.values
    }

    /**
     * Get class source and output directories
     */
    fun getClasspath() : Collection<ClasspathData> {
        val classDirs = mutableSetOf<ClasspathData>()
        result.topologicallySortedProjects.forEach { module ->
            val cpd = ClasspathData("Maven", module.name, module.name)
            cpd.classDirs.add(module.build.outputDirectory)
            cpd.classDirs.add(module.build.testOutputDirectory)
            cpd.sourceDirs.add(module.build.sourceDirectory)
            cpd.sourceDirs.add(module.build.testSourceDirectory)
            classDirs.add(cpd)
        }
        return classDirs
    }

    /**
     * Run the given task
     */
    fun runTask(taskName: String) : String {
        val result = connector.runTasks(arrayOf(taskName))
        println(result.exceptions.joinToString { it.toString() })
        return result.exceptions.joinToString { it.toString() }
    }

    /**
     * Run the given test class and return ?
     */
    fun runTest(className: String) {
    }

    /**
     * Process compilation errors into appropriate information for consumption
     */
    private fun processCompilationResult(compresult: MavenExecutionResult) : CompileResult {
        if (!compresult.hasExceptions()) return CompileResult()
        val result = CompileResult()
        compresult.exceptions.forEach {
            when (val c = it.cause) {
                is MojoFailureException -> result.messages.addAll(extractCompilationErrors(c.longMessage))
            }
        }
        return result
    }

    /**
     * Extract compilation errors from the long message; there can be multiple
     */
    private fun extractCompilationErrors(message: String) : Collection<CompileMessage> {
        val results = mutableListOf<CompileMessage>()
        message.split("\n").forEach {
            val matcher = ERROR_PATTERN.toPattern().matcher(it)
            if (matcher.matches()) {
                val file = matcher.group(1)
                val line = matcher.group(2).toInt()
                val column = matcher.group(3).toInt()
                val message = matcher.group(4)
                results.add(CompileMessage(file, line, column, message))
            }
        }
        return results
    }

}