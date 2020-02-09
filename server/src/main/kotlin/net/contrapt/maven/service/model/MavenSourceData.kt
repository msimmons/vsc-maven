package net.contrapt.maven.service.model

import net.contrapt.jvmcode.model.DependencyData
import net.contrapt.jvmcode.model.DependencySourceData

class MavenSourceData(
    override val source: String,
    override val description: String,
    override val dependencies: Collection<DependencyData>
) : DependencySourceData
