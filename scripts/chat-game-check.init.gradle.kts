// Runs the Bukkit-free chat game checks without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/chat-game-check.init.gradle.kts chatGameCheck   (issue #1395)
// Compiles scripts/ChatGameCheck.kt against the plugin's main classes and runs it from
// the repo root.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("chatGameCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/ChatGameCheck.kt"))
            into(layout.buildDirectory.dir("chat-game-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("chatGameCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        tasks.register("chatGameCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the chat game checks in scripts/ChatGameCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("ChatGameCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
