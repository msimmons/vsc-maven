package net.contrapt.maven.service

import io.kotlintest.matchers.shouldBe
import org.junit.Test

/**
 * Created by mark on 6/17/17.
 */
class MavenServiceSpec {

    var rootDir = System.getProperty("test.rootDir") ?: "/home/mark/work/vsc-maven"

    val projectDir = "${rootDir}/server/src/test/resources/test-project"

    val service = MavenService(projectDir, rootDir)

    @Test
    fun testRefresh() {
        service.refresh()
        val deps = service.getDependencies()
        deps.size shouldBe 2
        deps.filter { it.transitive }.size shouldBe 1
        val tasks = service.getTasks()
    }

}