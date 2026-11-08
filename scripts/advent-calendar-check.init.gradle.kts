// Runs the Advent Calendar checks (issue #1424) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/advent-calendar-check.init.gradle.kts adventCalendarCheck
// Compiles scripts/AdventCalendarCheck.kt against the plugin's main classes and runs it from the repo
// root. Paper ships the SQLite driver on a real server; here it's added to this check's runtime
// only, and the database it opens lives in a fresh temp directory.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("adventCalendarCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/AdventCalendarCheck.kt"))
            into(layout.buildDirectory.dir("advent-calendar-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("adventCalendarCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        dependencies.add(check.runtimeOnlyConfigurationName, "org.xerial:sqlite-jdbc:3.46.1.3")
        tasks.register("adventCalendarCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the Advent Calendar checks in scripts/AdventCalendarCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("AdventCalendarCheckKt")
            workingDir = rootProject.projectDir
            // Paper API (YamlConfiguration) is Java 25 bytecode, so this check runs on a Java 25 launcher.
            val toolchains = project.extensions.getByType(JavaToolchainService::class.java)
            javaLauncher.set(toolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
        }
    }
}
