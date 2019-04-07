package net.contrapt.maven.model

data class DependencySource(
        val name: String,
        val description: String,
        val dependencies: Collection<DependencyData> = mutableSetOf()
)