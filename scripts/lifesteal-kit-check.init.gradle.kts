// Runs the Lifesteal kit checks (issue #1577) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/lifesteal-kit-check.init.gradle.kts lifestealKitCheck
// Compiles scripts/LifestealKitCheck.kt against the plugin's main classes and runs it from the repo
// root. Paper ships the SQLite driver on a real server; here it's added to this check's runtime
// only, and the database it opens lives in a fresh temp directory.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("lifestealKitCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/LifestealKitCheck.kt"))
            into(layout.buildDirectory.dir("lifesteal-kit-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("lifestealKitCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        dependencies.add(check.runtimeOnlyConfigurationName, "org.xerial:sqlite-jdbc:3.46.1.3")
        tasks.register("lifestealKitCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the Lifesteal kit checks in scripts/LifestealKitCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("LifestealKitCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
