// Runs the Lifesteal team alliance and rivalry checks (issue #1405) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/lifesteal-alliance-check.init.gradle.kts lifestealAllianceCheck
// Compiles scripts/LifestealAllianceCheck.kt against the plugin's main classes and runs it from
// the repo root. The plugin shades the SQLite driver on a real server; here it's added to this
// check's runtime only, and the databases it opens live in fresh temp directories.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("lifestealAllianceCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/LifestealAllianceCheck.kt"))
            into(layout.buildDirectory.dir("lifesteal-alliance-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("lifestealAllianceCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        dependencies.add(check.runtimeOnlyConfigurationName, "org.xerial:sqlite-jdbc:3.46.1.3")
        tasks.register("lifestealAllianceCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the Lifesteal alliance and rivalry checks in scripts/LifestealAllianceCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("LifestealAllianceCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
