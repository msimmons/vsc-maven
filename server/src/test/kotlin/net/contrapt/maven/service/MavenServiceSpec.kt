package net.contrapt.maven.service

import io.kotlintest.matchers.beGreaterThan
import io.kotlintest.matchers.should
import io.kotlintest.matchers.shouldBe
import net.contrapt.maven.model.ConnectRequest
import org.junit.Test

/**
 * Created by mark on 6/17/17.
 */
class MavenServiceSpec {

    var rootDir = System.getProperty("test.rootDir") ?: "/home/mark/work/vsc-maven"

    val projectDir = "${rootDir}/server/src/test/resources/test-project"

    val service = MavenService(ConnectRequest(projectDir, rootDir))

    @Test
    fun testRefresh() {
        val result = service.refresh()
        val deps = result.second.dependencySources.first().dependencies
        deps.size shouldBe 2
        deps.filter { it.transitive }.size shouldBe 1
        service.getTasks().size shouldBe 17
    }

}