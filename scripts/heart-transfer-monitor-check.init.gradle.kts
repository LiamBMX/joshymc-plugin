// Runs the heart transfer monitoring checks (issue #1508) without a test source set in
// build.gradle.kts:
//   ./gradlew -I scripts/heart-transfer-monitor-check.init.gradle.kts heartTransferMonitorCheck
// Compiles scripts/HeartTransferMonitorCheck.kt against the plugin's main classes and runs it from
// the repo root. The plugin shades the SQLite driver on a real server; here it's added to this
// check's runtime only, and the databases it opens live in fresh temp directories.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("heartTransferMonitorCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/HeartTransferMonitorCheck.kt"))
            into(layout.buildDirectory.dir("heart-transfer-monitor-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("heartTransferMonitorCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        dependencies.add(check.runtimeOnlyConfigurationName, "org.xerial:sqlite-jdbc:3.46.1.3")
        tasks.register("heartTransferMonitorCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the heart transfer monitoring checks in scripts/HeartTransferMonitorCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("HeartTransferMonitorCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
