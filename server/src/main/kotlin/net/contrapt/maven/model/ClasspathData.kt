package net.contrapt.maven.model

data class ClasspathData(
    val source: String,
    val name: String,
    val module: String,
    val sourceDirs: MutableSet<String> = mutableSetOf(),
    val classDirs: MutableSet<String> = mutableSetOf()
)
