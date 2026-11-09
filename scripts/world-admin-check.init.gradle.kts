// Runs the world admin checks (issue #1353) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/world-admin-check.init.gradle.kts worldAdminCheck
// Compiles scripts/WorldAdminCheck.kt against the plugin's main classes and runs it from the repo root.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("worldAdminCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/WorldAdminCheck.kt"))
            into(layout.buildDirectory.dir("world-admin-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("worldAdminCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        tasks.register("worldAdminCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the world admin checks in scripts/WorldAdminCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("WorldAdminCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
