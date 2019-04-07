package net.contrapt.maven.model

data class DependencyData (
        val fileName: String,
        var sourceFileName: String?,
        val groupId: String,
        val artifactId: String,
        val version: String,
        val scopes: MutableSet<String> = mutableSetOf(),
        val modules: MutableSet<String> = mutableSetOf(),
        var transitive: Boolean = false,
        var resolved: Boolean = false
)