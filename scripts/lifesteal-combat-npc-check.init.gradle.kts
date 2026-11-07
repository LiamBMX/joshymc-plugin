// Runs the Lifesteal Combat NPC checks (issue #1402) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/lifesteal-combat-npc-check.init.gradle.kts lifestealCombatNpcCheck
// Compiles scripts/LifestealCombatNpcCheck.kt against the plugin's main classes and runs it from
// the repo root. The plugin shades the SQLite driver on a real server; here it's added to this
// check's runtime only, and the databases it opens live in fresh temp directories.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("lifestealCombatNpcCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/LifestealCombatNpcCheck.kt"))
            into(layout.buildDirectory.dir("lifesteal-combat-npc-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("lifestealCombatNpcCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        dependencies.add(check.runtimeOnlyConfigurationName, "org.xerial:sqlite-jdbc:3.46.1.3")
        tasks.register("lifestealCombatNpcCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the Lifesteal Combat NPC checks in scripts/LifestealCombatNpcCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("LifestealCombatNpcCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
