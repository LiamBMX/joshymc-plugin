// Runs the crate reward integrity checks (issue #1529) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/crate-reward-integrity-check.init.gradle.kts crateRewardIntegrityCheck
// Compiles scripts/CrateRewardIntegrityCheck.kt against the plugin's main classes and runs it from the repo root.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("crateRewardIntegrityCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/CrateRewardIntegrityCheck.kt"))
            into(layout.buildDirectory.dir("crate-reward-integrity-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("crateRewardIntegrityCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        // Paper ships the SQLite driver on a real server; here it's added to this check's runtime only.
        dependencies.add(check.runtimeOnlyConfigurationName, "org.xerial:sqlite-jdbc:3.46.1.3")
        tasks.register("crateRewardIntegrityCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the crate reward integrity checks in scripts/CrateRewardIntegrityCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("CrateRewardIntegrityCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
