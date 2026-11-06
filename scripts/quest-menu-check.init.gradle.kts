// Runs the /quests menu checks (issue #1385) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/quest-menu-check.init.gradle.kts questMenuCheck
// Compiles scripts/QuestMenuCheck.kt against the plugin's main classes and runs it from the repo root.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("questMenuCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/QuestMenuCheck.kt"))
            into(layout.buildDirectory.dir("quest-menu-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("questMenuCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        tasks.register("questMenuCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the /quests menu checks in scripts/QuestMenuCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("QuestMenuCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
