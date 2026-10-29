// Runs the Bukkit-free network identity checks (issue #1323) without a test source set in
// build.gradle.kts:
//   ./gradlew -I scripts/network-check.init.gradle.kts networkIdentityCheck
// Compiles scripts/NetworkIdentityCheck.kt against the plugin's main classes and runs it.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("networkCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/NetworkIdentityCheck.kt"))
            into(layout.buildDirectory.dir("network-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("networkCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        tasks.register("networkIdentityCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the network identity checks in scripts/NetworkIdentityCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("NetworkIdentityCheckKt")
        }
    }
}
