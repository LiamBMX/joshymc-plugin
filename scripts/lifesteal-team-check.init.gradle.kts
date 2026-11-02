// Runs the Lifesteal team checks (issue #1346) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/lifesteal-team-check.init.gradle.kts lifestealTeamCheck
// Compiles scripts/LifestealTeamCheck.kt against the plugin's main classes and runs it from the
// repo root. The plugin shades the SQLite driver on a real server; here it's added to this
// check's runtime only, and the databases it opens live in fresh temp directories.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("lifestealTeamCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/LifestealTeamCheck.kt"))
            into(layout.buildDirectory.dir("lifesteal-team-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("lifestealTeamCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        dependencies.add(check.runtimeOnlyConfigurationName, "org.xerial:sqlite-jdbc:3.46.1.3")
        tasks.register("lifestealTeamCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the Lifesteal team checks in scripts/LifestealTeamCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("LifestealTeamCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
