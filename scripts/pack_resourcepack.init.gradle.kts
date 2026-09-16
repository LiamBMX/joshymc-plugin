import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// Repackages resourcepack/ (the pack SOURCE tree) into src/main/resources/resourcepack.zip,
// the artifact ResourcePackManager actually bundles into the plugin jar and self-hosts to players.
// The two are NOT kept in sync automatically — run this after touching anything under resourcepack/.
// Run: ./gradlew -I scripts/pack_resourcepack.init.gradle.kts packResourcepack

gradle.rootProject {
    tasks.register("packResourcepack") {
        doLast {
            val sourceDir = File(rootDir, "resourcepack")
            val outFile = File(rootDir, "src/main/resources/resourcepack.zip")
            require(sourceDir.isDirectory) { "resourcepack/ source directory not found" }

            val files = sourceDir.walkTopDown().filter { it.isFile }.sortedBy { it.path }.toList()
            ZipOutputStream(outFile.outputStream()).use { zos ->
                for (file in files) {
                    val relativePath = file.relativeTo(sourceDir).invariantSeparatorsPath
                    zos.putNextEntry(ZipEntry(relativePath))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            println("Packed ${files.size} files into ${outFile.relativeTo(rootDir)}")
        }
    }
}
