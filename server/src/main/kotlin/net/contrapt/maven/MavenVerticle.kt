package net.contrapt.maven

import io.vertx.core.*
import io.vertx.core.json.JsonObject
import io.vertx.core.logging.LoggerFactory
import net.contrapt.maven.model.ConnectRequest
import net.contrapt.maven.model.ConnectResult
import net.contrapt.maven.service.MavenService

class MavenVerticle : AbstractVerticle() {

    private val logger = LoggerFactory.getLogger(javaClass)
    private lateinit var mavenService : MavenService

    override fun start() {

        /**
         * Connect to the requested projectDir returning tasks and dependencies
         */
        vertx.eventBus().consumer<JsonObject>("maven.connect") { message ->
            vertx.executeBlocking(Handler<Promise<JsonObject>> { future ->
                try {
                    val request = message.body().mapTo(ConnectRequest::class.java)
                    mavenService = MavenService(request)
                    val result = mavenService.refresh()
                    vertx.eventBus().send("jvmcode.update-project", JsonObject.mapFrom(result.second))
                    future.complete(JsonObject.mapFrom(result.first))
                } catch (e: Exception) {
                    future.fail(e)
                }
            }, false, Handler { ar ->
                if (ar.failed()) {
                    logger.info("In failed branch ${ar.cause()}")
                    val error = ar.cause().toString()
                    message.reply(JsonObject.mapFrom(ConnectResult(listOf(), listOf(error))))
                } else {
                    message.reply(ar.result())
                }
            })
        }

        /**
         * Refresh maven project (usually when one of the maven related files change)
         */
        vertx.eventBus().consumer<JsonObject>("maven.refresh") { message ->
            vertx.executeBlocking(Handler<Promise<JsonObject>> { future ->
                try {
                    val result = mavenService.refresh()
                    vertx.eventBus().send("jvmcode.update-project", JsonObject.mapFrom(result.second))
                    future.complete(JsonObject.mapFrom(result.first))
                } catch (e: Exception) {
                    future.fail(e)
                }
            }, false, Handler { ar ->
                if (ar.failed()) {
                    logger.info("In failed branch ${ar.cause()}")
                    val error = ar.cause().toString()
                    message.reply(JsonObject.mapFrom(ConnectResult(listOf(), listOf(error))))
                } else {
                    message.reply(ar.result())
                }
            })
        }

        /**
         * Run the given task in this project
         */
        vertx.eventBus().consumer<JsonObject>("maven.run-task") { message ->
            vertx.executeBlocking(Handler<Promise<JsonObject>> { future ->
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

    }

}