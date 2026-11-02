// Runs the Lifesteal heart system checks (issue #1341) without a test source set in
// build.gradle.kts:
//   ./gradlew -I scripts/lifesteal-heart-check.init.gradle.kts lifestealHeartCheck
// Compiles scripts/LifestealHeartCheck.kt against the plugin's main classes and runs it from the
// repo root. The plugin shades the SQLite driver on a real server; here it's added to this
// check's runtime only, and the databases it opens live in fresh temp directories.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("lifestealHeartCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/LifestealHeartCheck.kt"))
            into(layout.buildDirectory.dir("lifesteal-heart-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("lifestealHeartCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        dependencies.add(check.runtimeOnlyConfigurationName, "org.xerial:sqlite-jdbc:3.46.1.3")
        tasks.register("lifestealHeartCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the Lifesteal heart checks in scripts/LifestealHeartCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("LifestealHeartCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
