package net.contrapt.maven

import io.vertx.core.AbstractVerticle
import io.vertx.core.AsyncResult
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.json.JsonObject
import io.vertx.core.logging.LoggerFactory
import net.contrapt.maven.model.DependencySource
import net.contrapt.maven.model.MavenProject
import net.contrapt.maven.service.MavenService

class MavenVerticle : AbstractVerticle() {

    private val logger = LoggerFactory.getLogger(javaClass)
    private lateinit var mavenService : MavenService

    override fun start() {

        /**
         * Connect to the requested projectDir returning tasks and dependencies
         */
        vertx.eventBus().consumer<JsonObject>("maven.connect") { message ->
            vertx.executeBlocking(Handler<Future<JsonObject>> { future ->
                try {
                    val projectDir = message.body().getString("projectDir")
                    val extensionDir = message.body().getString("extensionDir")
                    mavenService = MavenService(projectDir, extensionDir)
                    mavenService.refresh()
                    val source = DependencySource("Maven", "Maven", mavenService.getDependencies())
                    val project = MavenProject(mavenService.getTasks(), setOf(source), mavenService.getClasspath())
                    val projectJson = JsonObject.mapFrom(project)
                    vertx.eventBus().publish("jvmcode.update-project", JsonObject().put("source", "Maven").put("project", projectJson))
                    future.complete(projectJson)
                } catch (e: Exception) {
                    logger.error("Opening a project", e)
                    future.fail(e)
                }
            }, false, Handler { ar ->
                if (ar.failed()) {
                    message.fail(1, ar.cause().toString())
                } else {
                    message.reply(ar.result())
                }
            })
        }

        /**
         * Refresh maven project (usually when one of the maven related files change)
         */
        vertx.eventBus().consumer<JsonObject>("maven.refresh") { message ->
            vertx.executeBlocking(Handler<Future<JsonObject>> { future ->
                try {
                    mavenService.refresh()
                    val source = DependencySource("Maven", "Maven", mavenService.getDependencies())
                    val project = MavenProject(mavenService.getTasks(), setOf(source), mavenService.getClasspath())
                    val projectJson = JsonObject.mapFrom(project)
                    vertx.eventBus().publish("jvmcode.update-project", JsonObject().put("source", "Maven").put("project", projectJson))
                    future.complete(projectJson)
                } catch (e: Exception) {
                    logger.error("Opening a project", e)
                    future.fail(e)
                }
            }, false, Handler { ar ->
                if (ar.failed()) {
                    message.fail(1, ar.cause().toString())
                } else {
                    message.reply(ar.result())
                }
            })
        }

        /**
         * Run the given task in this project
         */
        vertx.eventBus().consumer<JsonObject>("maven.run-task") { message ->
            vertx.executeBlocking(Handler<Future<JsonObject>> { future ->
                try {
                    val task = message.body().getString("task")
                    future.complete(JsonObject().put("taskOutput", mavenService.runTask(task)))
                } catch (e: Exception) {
                    logger.error("Running task", e)
                    future.fail(e)
                }
            }, false, Handler<AsyncResult<JsonObject>> { ar ->
                if (ar.failed()) {
                    message.fail(1, ar.cause().toString())
                } else message.reply(ar.result())
            })
        }

        /**
         * Compile the project and return results (errors and warnings)
         */
        vertx.eventBus().consumer<JsonObject>("maven.compile") { message ->
            vertx.executeBlocking(Handler<Future<JsonObject>> { future ->
                try {
                    val result = mavenService.compile()
                    future.complete(JsonObject.mapFrom(result))
                } catch (e: Exception) {
                    logger.error("Compiling project", e)
                    future.fail(e)
                }
            }, false, Handler<AsyncResult<JsonObject>> { ar ->
                if (ar.failed()) {
                    message.fail(1, ar.cause().toString())
                } else message.reply(ar.result())
            })
        }
    }

}