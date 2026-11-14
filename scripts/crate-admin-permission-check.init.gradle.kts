// Runs the crate admin permission and editor audit checks (issue #1541) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/crate-admin-permission-check.init.gradle.kts crateAdminPermissionCheck
// Compiles scripts/CrateAdminPermissionCheck.kt against the plugin's main classes and runs it from the repo root.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("crateAdminPermissionCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/CrateAdminPermissionCheck.kt"))
            into(layout.buildDirectory.dir("crate-admin-permission-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("crateAdminPermissionCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        tasks.register("crateAdminPermissionCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the crate admin permission checks in scripts/CrateAdminPermissionCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("CrateAdminPermissionCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
