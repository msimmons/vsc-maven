package net.contrapt.maven.model

data class CompileResult(
        val messages: MutableList<CompileMessage> = mutableListOf()
)
