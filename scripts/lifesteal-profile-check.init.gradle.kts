// Runs the Lifesteal profile, achievement and leaderboard checks (issue #1429) without a test source
// set in build.gradle.kts:
//   ./gradlew -I scripts/lifesteal-profile-check.init.gradle.kts lifestealProfileCheck
// Compiles scripts/LifestealProfileCheck.kt against the plugin's main classes and runs it from the
// repo root. The plugin shades the SQLite driver on a real server; here it's added to this check's
// runtime only, and the databases it opens live in fresh temp directories.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("lifestealProfileCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/LifestealProfileCheck.kt"))
            into(layout.buildDirectory.dir("lifesteal-profile-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("lifestealProfileCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        dependencies.add(check.runtimeOnlyConfigurationName, "org.xerial:sqlite-jdbc:3.46.1.3")
        tasks.register("lifestealProfileCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the Lifesteal profile checks in scripts/LifestealProfileCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("LifestealProfileCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
