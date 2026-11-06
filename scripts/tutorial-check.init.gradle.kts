// Runs the guided tutorial checks (issue #1387) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/tutorial-check.init.gradle.kts tutorialCheck
// Compiles scripts/TutorialCheck.kt against the plugin's main classes and runs it from the repo
// root. Paper ships the SQLite driver on a real server; here it's added to this check's runtime
// only, and the database it opens lives in a fresh temp directory.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("tutorialCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/TutorialCheck.kt"))
            into(layout.buildDirectory.dir("tutorial-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("tutorialCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        dependencies.add(check.runtimeOnlyConfigurationName, "org.xerial:sqlite-jdbc:3.46.1.3")
        tasks.register("tutorialCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the guided tutorial checks in scripts/TutorialCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("TutorialCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
