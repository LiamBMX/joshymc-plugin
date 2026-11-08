// Runs the Lifesteal rank checks (issue #1428) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/lifesteal-rank-check.init.gradle.kts lifestealRankCheck
// Compiles scripts/LifestealRankCheck.kt against the plugin's main classes and runs it from the repo
// root. Paper ships the SQLite driver on a real server; here it's added to this check's runtime
// only, and the database it opens lives in a fresh temp directory.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("lifestealRankCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/LifestealRankCheck.kt"))
            into(layout.buildDirectory.dir("lifesteal-rank-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("lifestealRankCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        dependencies.add(check.runtimeOnlyConfigurationName, "org.xerial:sqlite-jdbc:3.46.1.3")
        tasks.register("lifestealRankCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the Lifesteal rank checks in scripts/LifestealRankCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("LifestealRankCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
