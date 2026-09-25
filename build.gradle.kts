plugins {
    kotlin("jvm") version "2.3.20"
    id("com.gradleup.shadow") version "8.3.0"
    id("xyz.jpenilla.run-paper") version "2.3.1"
}

group = "com.liam"
version = providers.gradleProperty("releaseVersion").orElse("1.0-SNAPSHOT").get()

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc-repo"
    }
}

dependencies {
    // Plain Paper API: the plugin has no NMS code left (NPCs use the Mannequin entity).
    compileOnly("io.papermc.paper:paper-api:26.2.build.128-stable")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("net.dv8tion:JDA:5.2.3") {
        exclude(module = "opus-java") // not needed, saves ~2MB
    }
}

tasks {
    runServer {
        // Configure the Minecraft version for our task.
        // This is the only required configuration besides applying the plugin.
        // Your plugin's jar (or shadowJar if present) will be used automatically.
        minecraftVersion("26.2")
        // Paper 26.x runs on Java 25.
        javaLauncher.set(project.javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    }
}

val targetJavaVersion = 21
kotlin {
    jvmToolchain(targetJavaVersion)
}

java {
    // paper-api 26.x is published for Java 25. We still emit Java 21 bytecode (it runs on
    // Paper's Java 25), so let Gradle resolve the newer API instead of rejecting it.
    disableAutoTargetJvm()
}

// Write final JARs into a top-level `jar/` directory so collaborators can grab
// the built artifact straight from git without running Gradle themselves.
val jarOutputDir = layout.projectDirectory.dir("jar")

tasks.jar {
    destinationDirectory.set(jarOutputDir)
}

tasks.shadowJar {
    destinationDirectory.set(jarOutputDir)

    // Relocate JDA and its transitive dependencies to avoid conflicts
    // with Paper's bundled SLF4J, OkHttp, Gson, etc.
    relocate("net.dv8tion.jda", "com.liam.joshymc.libs.jda")
    relocate("okhttp3", "com.liam.joshymc.libs.okhttp3")
    relocate("okio", "com.liam.joshymc.libs.okio")
    relocate("gnu.trove", "com.liam.joshymc.libs.trove")
    relocate("com.neovisionaries.ws", "com.liam.joshymc.libs.nv_ws")
    relocate("org.apache.commons.collections4", "com.liam.joshymc.libs.commons_collections4")
}

tasks.build {
    dependsOn("shadowJar")
}

// Zip resourcepack/ into the JAR on every build so the served pack is never stale.
// Run `py -m art.build` in resourcepack/ first when item art changes.
val resourcePackZip by tasks.registering(Zip::class) {
    from("resourcepack") {
        include("pack.mcmeta", "assets/**")
    }
    archiveFileName.set("resourcepack.zip")
    destinationDirectory.set(layout.buildDirectory.dir("generated/resourcepack"))
    // Same content -> same bytes -> same hash, so players only re-download real changes.
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.processResources {
    from(resourcePackZip)
    val props = mapOf("version" to version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(props)
    }
}
