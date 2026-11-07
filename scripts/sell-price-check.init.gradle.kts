// Runs the /sell price catalog checks (issue #1416) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/sell-price-check.init.gradle.kts sellPriceCheck
// Compiles scripts/SellPriceCheck.kt against the plugin's main classes and runs it from the repo root.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("sellPriceCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/SellPriceCheck.kt"))
            into(layout.buildDirectory.dir("sell-price-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("sellPriceCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        tasks.register("sellPriceCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the /sell price catalog checks in scripts/SellPriceCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("SellPriceCheckKt")
            workingDir = rootProject.projectDir
            // The bundled catalog is validated against org.bukkit.Material, which paper-api ships
            // as Java 25 bytecode (the plugin itself targets 21), so this runs on a Java 25 launcher.
            val toolchains = project.extensions.getByType(JavaToolchainService::class.java)
            javaLauncher.set(toolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
        }
    }
}
