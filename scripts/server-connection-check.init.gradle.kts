// Runs the /ip and ServerBar connection info checks (issue #1359) without a test source set in
// build.gradle.kts:
//   ./gradlew -I scripts/server-connection-check.init.gradle.kts serverConnectionCheck
// Compiles scripts/ServerConnectionCheck.kt against the plugin's main classes and runs it from
// the repo root.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("serverConnectionCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/ServerConnectionCheck.kt"))
            into(layout.buildDirectory.dir("server-connection-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("serverConnectionCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        tasks.register("serverConnectionCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the connection info checks in scripts/ServerConnectionCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("ServerConnectionCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
