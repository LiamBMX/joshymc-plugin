// Runs the dedicated Lifesteal server bootstrap checks (issue #1336) without a test source set in
// build.gradle.kts:
//   ./gradlew -I scripts/lifesteal-check.init.gradle.kts lifestealCheck
// Compiles scripts/LifestealCheck.kt against the plugin's main classes and runs it. The plugin
// shades the SQLite driver on a real server; here it's added to this check's runtime only, and
// the databases it opens live in a fresh temp directory.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("lifestealCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/LifestealCheck.kt"))
            into(layout.buildDirectory.dir("lifesteal-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("lifestealCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        dependencies.add(check.runtimeOnlyConfigurationName, "org.xerial:sqlite-jdbc:3.46.1.3")
        tasks.register("lifestealCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the Lifesteal bootstrap checks in scripts/LifestealCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("LifestealCheckKt")
        }
    }
}
