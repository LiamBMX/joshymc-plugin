// Runs the staff permission checks (issue #1383) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/staff-permission-check.init.gradle.kts staffPermissionCheck
// Compiles scripts/StaffPermissionCheck.kt against the plugin's main classes and runs it from the repo root.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("staffPermissionCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/StaffPermissionCheck.kt"))
            into(layout.buildDirectory.dir("staff-permission-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("staffPermissionCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        tasks.register("staffPermissionCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the staff permission checks in scripts/StaffPermissionCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("StaffPermissionCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
