// Runs the AFK spawn checks (issue #1596) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/afk-spawn-check.init.gradle.kts afkSpawnCheck
// Compiles scripts/AfkSpawnCheck.kt against the plugin's main classes and runs it from the repo
// root. Paper ships the SQLite driver on a real server; here it's added to this check's runtime
// only, and the database it opens lives in a fresh temp directory.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("afkSpawnCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/AfkSpawnCheck.kt"))
            into(layout.buildDirectory.dir("afk-spawn-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("afkSpawnCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        dependencies.add(check.runtimeOnlyConfigurationName, "org.xerial:sqlite-jdbc:3.46.1.3")
        tasks.register("afkSpawnCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the AFK spawn checks in scripts/AfkSpawnCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("AfkSpawnCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
