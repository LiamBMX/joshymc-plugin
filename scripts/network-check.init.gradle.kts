// Runs the Bukkit-free network checks without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/network-check.init.gradle.kts networkIdentityCheck   (issue #1323)
//   ./gradlew -I scripts/network-check.init.gradle.kts networkTransferCheck   (issue #1331)
// Compiles scripts/NetworkIdentityCheck.kt and scripts/TransferCheck.kt against the plugin's
// main classes and runs one of them.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("networkCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/NetworkIdentityCheck.kt"), rootProject.file("scripts/TransferCheck.kt"))
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
        tasks.register("networkTransferCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the cross-server transfer checks in scripts/TransferCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("TransferCheckKt")
        }
    }
}
