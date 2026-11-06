// Runs the purchasable rank perk checks (issue #1381) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/rank-perk-check.init.gradle.kts rankPerkCheck
// Compiles scripts/RankPerkCheck.kt against the plugin's main classes and runs it from the repo root.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("rankPerkCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/RankPerkCheck.kt"))
            into(layout.buildDirectory.dir("rank-perk-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("rankPerkCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        tasks.register("rankPerkCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the purchasable rank perk checks in scripts/RankPerkCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("RankPerkCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
