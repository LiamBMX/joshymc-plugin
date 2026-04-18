plugins {
    kotlin("jvm") version "2.3.20"
    id("com.gradleup.shadow") version "8.3.0"
    id("xyz.jpenilla.run-paper") version "2.3.1"
    // paperweight-userdev gives us direct compile-time access to mojang-mapped NMS
    // (no more reflection guessing for player NPCs / packet construction).
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.18"
}

group = "com.liam"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc-repo"
    }
}

dependencies {
    // Replaces the old compileOnly paper-api dep — now we get the full
    // mojang-mapped Paper server JAR for compile-time NMS access.
    paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")
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
        minecraftVersion("1.21.11")
    }
}

val targetJavaVersion = 21
kotlin {
    jvmToolchain(targetJavaVersion)
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

// paperweight's reobfJar runs after the assembled JAR. We don't need it because
// Paper 1.20.5+ runs mojang-mapped plugins natively via the plugin remapper.
// If you ever need the reobfuscated jar (for older Paper or Spigot), uncomment:
// tasks.assemble { dependsOn(tasks.reobfJar) }

tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(props)
    }
}
