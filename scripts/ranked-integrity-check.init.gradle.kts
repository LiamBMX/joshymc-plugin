// Runs the Ranked Team Wars match-integrity checks (issue #1498) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/ranked-integrity-check.init.gradle.kts rankedIntegrityCheck
// Compiles scripts/RankedIntegrityCheck.kt against the plugin's main classes and runs it from
// the repo root. The plugin shades the SQLite driver on a real server; here it's added to this
// check's runtime only, and the databases it opens live in fresh temp directories.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("rankedIntegrityCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/RankedIntegrityCheck.kt"))
            into(layout.buildDirectory.dir("ranked-integrity-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("rankedIntegrityCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        dependencies.add(check.runtimeOnlyConfigurationName, "org.xerial:sqlite-jdbc:3.46.1.3")
        tasks.register("rankedIntegrityCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the Ranked Team Wars match-integrity checks in scripts/RankedIntegrityCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("RankedIntegrityCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
