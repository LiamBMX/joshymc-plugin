// Runs the bundled voucher Chat Tag checks (issue #1391) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/voucher-tag-check.init.gradle.kts voucherTagCheck
// Compiles scripts/VoucherTagCheck.kt against the plugin's main classes (and Paper API, for
// YamlConfiguration and Adventure) and runs it from the repo root. No server is started.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("voucherTagCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/VoucherTagCheck.kt"))
            into(layout.buildDirectory.dir("voucher-tag-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("voucherTagCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        tasks.register("voucherTagCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the voucher Chat Tag checks in scripts/VoucherTagCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("VoucherTagCheckKt")
            workingDir = rootProject.projectDir
            // Paper API (YamlConfiguration) is Java 25 bytecode, so this check runs on a Java 25 launcher.
            val toolchains = project.extensions.getByType(JavaToolchainService::class.java)
            javaLauncher.set(toolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
        }
    }
}
