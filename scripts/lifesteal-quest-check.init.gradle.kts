// Runs the Lifesteal quest checks (issue #1425) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/lifesteal-quest-check.init.gradle.kts lifestealQuestCheck
// Compiles scripts/LifestealQuestCheck.kt against the plugin's main classes and runs it from the
// repo root. The plugin shades the SQLite driver on a real server; here it's added to this check's
// runtime only, and the databases it opens live in fresh temp directories.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("lifestealQuestCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/LifestealQuestCheck.kt"))
            into(layout.buildDirectory.dir("lifesteal-quest-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("lifestealQuestCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        dependencies.add(check.runtimeOnlyConfigurationName, "org.xerial:sqlite-jdbc:3.46.1.3")
        tasks.register("lifestealQuestCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the Lifesteal quest checks in scripts/LifestealQuestCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("LifestealQuestCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
