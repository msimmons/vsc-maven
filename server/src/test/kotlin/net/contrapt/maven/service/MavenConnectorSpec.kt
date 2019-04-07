package net.contrapt.maven.service

import org.apache.maven.cli.MavenConnector
import org.apache.maven.lifecycle.LifecycleExecutionException
import org.apache.maven.project.ProjectBuildingException
import org.junit.Test

/**
 * Created by mark on 6/17/17.
 */
class MavenConnectorSpec {

    val rootDir = System.getProperty("test.rootDir") ?: "/home/mark/work/vsc-maven"

    val projectDir = "${rootDir}/server/src/test/resources/test-project"
    val badProjectDir = "${rootDir}/server/src/test/resources/bad-project"
    //val projectDir = "/home/mark/work/sandbox"
    val connector = MavenConnector(projectDir)

    @Test
    fun testConnect() {
        val connector = MavenConnector(projectDir)
        val result = connector.connect()
        println(result)
    }

    @Test
    fun testBadDirectory() {
        val connector = MavenConnector("foo")
        val result = connector.connect()
        println(result)
        //lifecycleexecutionexception
    }

    @Test
    fun testBadProject() {
        val connector = MavenConnector(badProjectDir)
        val result = connector.connect()
        result.exceptions.forEach {
            when (it) {
                is ProjectBuildingException -> it.results.forEach { it.problems.forEach { println(it) } }
            }
        }
        //projectbuildingexception
    }

    @Test
    fun testCompileError() {
        val connector = MavenConnector(projectDir)
        val result = connector.compile()
        result.exceptions.forEach {
            when (it) {
                is ProjectBuildingException -> it.results.forEach { it.problems.forEach { println("p ${it}") } }
                is LifecycleExecutionException -> println("e ${it.cause}")
            }
        }
        //lifecycle.cause compilationfailureexception
    }
}