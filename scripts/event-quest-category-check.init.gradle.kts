// Runs the Bukkit-free Event Quest category checks without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/event-quest-category-check.init.gradle.kts eventQuestCategoryCheck   (issue #1335)
// Compiles scripts/EventQuestCategoryCheck.kt against the plugin's main classes and runs it from
// the repo root.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("eventQuestCategoryCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/EventQuestCategoryCheck.kt"))
            into(layout.buildDirectory.dir("event-quest-category-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("eventQuestCategoryCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        tasks.register("eventQuestCategoryCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the Event Quest category checks in scripts/EventQuestCategoryCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("EventQuestCategoryCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
