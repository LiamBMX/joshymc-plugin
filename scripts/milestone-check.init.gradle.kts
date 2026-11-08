// Runs the Milestones checks (issue #1420) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/milestone-check.init.gradle.kts milestoneCheck
// Compiles scripts/MilestoneCheck.kt against the plugin's main classes and runs it from the repo
// root. Paper ships the SQLite driver on a real server; here it's added to this check's runtime
// only, and the database it opens lives in a fresh temp directory.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("milestoneCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/MilestoneCheck.kt"))
            into(layout.buildDirectory.dir("milestone-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("milestoneCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        dependencies.add(check.runtimeOnlyConfigurationName, "org.xerial:sqlite-jdbc:3.46.1.3")
        tasks.register("milestoneCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the Milestones checks in scripts/MilestoneCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("MilestoneCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
