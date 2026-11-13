// Runs the player rank security checks (issue #1521) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/rank-security-check.init.gradle.kts rankSecurityCheck
// Compiles scripts/RankSecurityCheck.kt against the plugin's main classes and runs it from the
// repo root. No server is started.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("rankSecurityCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/RankSecurityCheck.kt"))
            into(layout.buildDirectory.dir("rank-security-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("rankSecurityCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        tasks.register("rankSecurityCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the player rank security checks in scripts/RankSecurityCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("RankSecurityCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
