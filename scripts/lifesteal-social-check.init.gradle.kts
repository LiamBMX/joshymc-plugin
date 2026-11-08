// Runs the Lifesteal friends, privacy, messaging and chat-channel checks (issue #1430) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/lifesteal-social-check.init.gradle.kts lifestealSocialCheck
// Compiles scripts/LifestealSocialCheck.kt against the plugin's main classes and runs it from
// the repo root. The plugin shades the SQLite driver on a real server; here it's added to this
// check's runtime only, and the databases it opens live in fresh temp directories.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("lifestealSocialCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/LifestealSocialCheck.kt"))
            into(layout.buildDirectory.dir("lifesteal-social-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("lifestealSocialCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        dependencies.add(check.runtimeOnlyConfigurationName, "org.xerial:sqlite-jdbc:3.46.1.3")
        tasks.register("lifestealSocialCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the Lifesteal social checks in scripts/LifestealSocialCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("LifestealSocialCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
