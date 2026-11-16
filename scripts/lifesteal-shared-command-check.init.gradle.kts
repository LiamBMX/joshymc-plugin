// Runs the Lifesteal shared command checks (issue #1573) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/lifesteal-shared-command-check.init.gradle.kts lifestealSharedCommandCheck
// Compiles scripts/LifestealSharedCommandCheck.kt against the plugin's main classes and runs it.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("lifestealSharedCommandCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/LifestealSharedCommandCheck.kt"))
            into(layout.buildDirectory.dir("lifesteal-shared-command-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("sharedCommandCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        tasks.register("lifestealSharedCommandCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the Lifesteal shared command checks in scripts/LifestealSharedCommandCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("LifestealSharedCommandCheckKt")
        }
    }
}
