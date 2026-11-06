// Runs the /kit menu checks (issue #1379) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/kit-menu-check.init.gradle.kts kitMenuCheck
// Compiles scripts/KitMenuCheck.kt against the plugin's main classes and runs it from the repo root.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("kitMenuCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/KitMenuCheck.kt"))
            into(layout.buildDirectory.dir("kit-menu-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("kitMenuCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        tasks.register("kitMenuCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the /kit menu checks in scripts/KitMenuCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("KitMenuCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
