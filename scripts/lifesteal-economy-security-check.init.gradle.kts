// Runs the Lifesteal economy security checks (issue #1548) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/lifesteal-economy-security-check.init.gradle.kts lifestealEconomySecurityCheck
// Compiles scripts/LifestealEconomySecurityCheck.kt against the plugin's main classes and runs it from
// the repo root. The SQLite driver is added to this check's runtime only; its databases live in
// fresh temp directories.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("lifestealEconomySecurityCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/LifestealEconomySecurityCheck.kt"))
            into(layout.buildDirectory.dir("lifesteal-economy-security-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("lifestealEconomySecurityCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        dependencies.add(check.runtimeOnlyConfigurationName, "org.xerial:sqlite-jdbc:3.46.1.3")
        tasks.register("lifestealEconomySecurityCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the Lifesteal economy security checks in scripts/LifestealEconomySecurityCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("LifestealEconomySecurityCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
