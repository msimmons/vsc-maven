package net.contrapt.maven.service

import net.contrapt.jvmcode.model.DependencySourceData
import net.contrapt.jvmcode.model.PathData
import net.contrapt.maven.model.*
import net.contrapt.maven.service.model.MavenDependencyData
import net.contrapt.maven.service.model.MavenPathData
import net.contrapt.maven.service.model.MavenSourceData
import org.apache.maven.cli.MavenConnector
import org.apache.maven.execution.MavenExecutionResult
import java.io.File

/**
 * To connect to a maven project
 */
class MavenService(val request: ConnectRequest) {

    val connector = MavenConnector(request.projectDir)
    lateinit var mavenProject : MavenExecutionResult
        private set

    val SOURCE = "Maven"

    /**
     * Refresh the project model
     */
    fun refresh(): Pair<ConnectResult, ProjectData> {
        mavenProject = connector.connect()
        val errors = mavenProject.exceptions.map { it.toString() }
        val result = ConnectResult(getTasks(), errors)
        val project = ProjectData(SOURCE, getDependenciesSources(), getPathData())
        return result to project
    }

    fun getDescription() = "$SOURCE ${mavenProject.project.modelVersion} (${mavenProject.project.file.absolutePath})"

    fun getTasks() : Collection<String> {
        val tasks = mutableSetOf<String>()
        mavenProject.topologicallySortedProjects.forEach { project ->
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
    fun getDependenciesSources() : Collection<DependencySourceData> {
        val deps = mutableMapOf<String, MavenDependencyData>()
        mavenProject.topologicallySortedProjects.forEach { module ->
            module.artifacts.forEach { art ->
                val key = "${art.groupId}:${art.artifactId}:${art.version}:${art.classifier}"
                if (art.file?.exists() ?: false) {
                    val dep = deps.getOrPut(key, {
                        MavenDependencyData(art.file.absolutePath, art.groupId, art.artifactId, art.version)
                    })
                    dep.transitive = art.dependencyTrail.size > 2
                    dep.modules.add(module.name)
                    dep.scopes.add(art.scope)
                    val srcFile = File(art.file.absolutePath.replace(".jar", "-sources.jar"))
                    if (srcFile.exists()) dep.sourceFileName = srcFile.absolutePath
                }
            }
        }
        return listOf(MavenSourceData(SOURCE, getDescription(), deps.values))
    }

    /**
     * Get class source and output directories
     */
    fun getPathData() : Collection<PathData> {
        val pathDatas = mutableSetOf<MavenPathData>()
        mavenProject.topologicallySortedProjects.forEach { module ->
            val mainData = MavenPathData(SOURCE, "main", module.name)
            val testData = MavenPathData(SOURCE, "test", module.name)
            mainData.classDirs.add(module.build.outputDirectory)
            testData.classDirs.add(module.build.testOutputDirectory)
            mainData.sourceDirs.add(module.build.sourceDirectory)
            testData.sourceDirs.add(module.build.testSourceDirectory)
            pathDatas.add(mainData)
            pathDatas.add(testData)
        }
        return pathDatas
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

}