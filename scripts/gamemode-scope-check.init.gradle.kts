// Runs the gamemode scope checks (issue #1329) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/gamemode-scope-check.init.gradle.kts gamemodeScopeCheck
// Compiles scripts/GamemodeScopeCheck.kt against the plugin's main classes and runs it.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("gamemodeScopeCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/GamemodeScopeCheck.kt"))
            into(layout.buildDirectory.dir("gamemode-scope-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("scopeCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        tasks.register("gamemodeScopeCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the gamemode scope checks in scripts/GamemodeScopeCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("GamemodeScopeCheckKt")
        }
    }
}
