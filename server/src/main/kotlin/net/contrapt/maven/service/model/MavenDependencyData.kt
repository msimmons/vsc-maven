package net.contrapt.maven.service.model

import net.contrapt.jvmcode.model.DependencyData

class MavenDependencyData(
    override val fileName: String,
    override val groupId: String,
    override val artifactId: String,
    override val version: String,
    override val modules: MutableSet<String> = mutableSetOf(),
    override val scopes: MutableSet<String> = mutableSetOf(),
    override var sourceFileName: String? = null,
    override var resolved: Boolean = false,
    override var transitive: Boolean = false
) : DependencyData {
    override val jmod: String? = null
}
