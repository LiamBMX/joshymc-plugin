// Runs the shared GUI icon checks (issue #1355) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/gui-icon-check.init.gradle.kts guiIconCheck
// Compiles scripts/GuiIconCheck.kt against the plugin's main classes and runs it from the repo root.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("guiIconCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/GuiIconCheck.kt"))
            into(layout.buildDirectory.dir("gui-icon-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("guiIconCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        tasks.register("guiIconCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the GUI icon checks in scripts/GuiIconCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("GuiIconCheckKt")
            workingDir = rootProject.projectDir
            // The item classes touch org.bukkit.Material, which paper-api ships as Java 25 bytecode
            // (the plugin itself targets 21), so this check runs on a Java 25 launcher.
            val toolchains = project.extensions.getByType(JavaToolchainService::class.java)
            javaLauncher.set(toolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
        }
    }
}
