package net.contrapt.maven.model

import net.contrapt.jvmcode.model.DependencySourceData
import net.contrapt.jvmcode.model.PathData
import net.contrapt.jvmcode.model.ProjectUpdateData

class ProjectData(
    override val source: String,
    override val dependencySources: Collection<DependencySourceData>,
    override val paths: Collection<PathData>
) : ProjectUpdateData

