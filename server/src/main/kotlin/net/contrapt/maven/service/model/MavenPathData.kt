package net.contrapt.maven.service.model

import net.contrapt.jvmcode.model.PathData

class MavenPathData(
    override val source: String,
    override val name: String,
    override val module: String,
    override val sourceDirs: MutableSet<String> = mutableSetOf(),
    override val classDirs: MutableSet<String> = mutableSetOf()
) : PathData
