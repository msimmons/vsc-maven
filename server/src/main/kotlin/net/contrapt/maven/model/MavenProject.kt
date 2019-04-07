package net.contrapt.maven.model

data class MavenProject(
        val tasks: Collection<String>,
        val dependencySources: Collection<DependencySource>,
        val classDirs: Collection<ClasspathData>,
        val classpath: String = ""
)