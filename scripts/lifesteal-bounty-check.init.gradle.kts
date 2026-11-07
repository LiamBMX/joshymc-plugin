// Runs the Lifesteal bounty checks (issue #1401) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/lifesteal-bounty-check.init.gradle.kts lifestealBountyCheck
// Compiles scripts/LifestealBountyCheck.kt against the plugin's main classes and runs it from the
// repo root. The plugin shades the SQLite driver on a real server; here it's added to this check's
// runtime only, and the databases it opens live in fresh temp directories.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("lifestealBountyCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/LifestealBountyCheck.kt"))
            into(layout.buildDirectory.dir("lifesteal-bounty-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("lifestealBountyCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        dependencies.add(check.runtimeOnlyConfigurationName, "org.xerial:sqlite-jdbc:3.46.1.3")
        tasks.register("lifestealBountyCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the Lifesteal bounty checks in scripts/LifestealBountyCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("LifestealBountyCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
