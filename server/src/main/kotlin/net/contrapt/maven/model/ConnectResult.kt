package net.contrapt.maven.model

data class ConnectResult(
    val tasks: Collection<String>,
    val errors: Collection<String>
)