// Runs the Ranked Team Wars infrastructure checks (issue #1361) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/lifesteal-ranked-check.init.gradle.kts lifestealRankedCheck
// Compiles scripts/LifestealRankedCheck.kt against the plugin's main classes and runs it from
// the repo root. The plugin shades the SQLite driver on a real server; here it's added to this
// check's runtime only, and the databases it opens live in fresh temp directories.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("lifestealRankedCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/LifestealRankedCheck.kt"))
            into(layout.buildDirectory.dir("lifesteal-ranked-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("lifestealRankedCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        dependencies.add(check.runtimeOnlyConfigurationName, "org.xerial:sqlite-jdbc:3.46.1.3")
        tasks.register("lifestealRankedCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the Ranked Team Wars infrastructure checks in scripts/LifestealRankedCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("LifestealRankedCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
