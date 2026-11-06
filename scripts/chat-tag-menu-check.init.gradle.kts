// Runs the /tags menu checks (issue #1393) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/chat-tag-menu-check.init.gradle.kts chatTagMenuCheck
// Compiles scripts/ChatTagMenuCheck.kt against the plugin's main classes (and Paper API, for
// YamlConfiguration) and runs it from the repo root. No server is started.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("chatTagMenuCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/ChatTagMenuCheck.kt"))
            into(layout.buildDirectory.dir("chat-tag-menu-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("chatTagMenuCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        tasks.register("chatTagMenuCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the /tags menu checks in scripts/ChatTagMenuCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("ChatTagMenuCheckKt")
            workingDir = rootProject.projectDir
            // Paper API (YamlConfiguration) is Java 25 bytecode, so this check runs on a Java 25 launcher.
            val toolchains = project.extensions.getByType(JavaToolchainService::class.java)
            javaLauncher.set(toolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
        }
    }
}
