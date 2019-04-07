package net.contrapt.maven.model

data class CompileMessage(
        val file: String,
        val line: Int,
        val column: Int,
        val message: String
)
