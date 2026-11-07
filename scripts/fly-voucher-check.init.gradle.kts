// Runs the Fly Voucher checks (issue #1408) without a test source set in build.gradle.kts:
//   ./gradlew -I scripts/fly-voucher-check.init.gradle.kts flyVoucherCheck
// Compiles scripts/FlyVoucherCheck.kt against the plugin's main classes and runs it from the
// repo root. No server or LuckPerms is started.

allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        val sources = tasks.register("flyVoucherCheckSources", Sync::class.java) {
            from(rootProject.file("scripts/FlyVoucherCheck.kt"))
            into(layout.buildDirectory.dir("fly-voucher-check-src"))
        }
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val check = sourceSets.create("flyVoucherCheck") {
            java.setSrcDirs(listOf(sources))
            resources.setSrcDirs(emptyList<Any>())
            compileClasspath += main.output + main.compileClasspath
            runtimeClasspath += output + compileClasspath
        }
        tasks.register("flyVoucherCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the Fly Voucher checks in scripts/FlyVoucherCheck.kt."
            classpath = check.runtimeClasspath
            mainClass.set("FlyVoucherCheckKt")
            workingDir = rootProject.projectDir
        }
    }
}
