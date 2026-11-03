// Runs the combat teleport checks (issue #1356) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/combat-teleport-check.init.gradle.kts combatTeleportCheck
// Compiles scripts/CombatTeleportCheck.kt against the plugin's main classes and runs it from the
// repo root (it reads the plugin sources to check the teleport paths' wiring).

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("combatTeleportCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/CombatTeleportCheck.kt"))
            into(layout.buildDirectory.dir("combat-teleport-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("combatTeleportCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        tasks.register("combatTeleportCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the combat teleport checks in scripts/CombatTeleportCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("CombatTeleportCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
