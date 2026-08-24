package io.cognotik.zipdiff.plugin

import io.cognotik.zipdiff.canonical.CanonicalProfile
import io.cognotik.zipdiff.canonical.Canonicalizer
import io.cognotik.zipdiff.diff.DiffGenerator
import io.cognotik.zipdiff.`package`.PatchMetadata
import io.cognotik.zipdiff.`package`.PatchPackager
import io.cognotik.zipdiff.signature.SignatureBlock
import io.cognotik.zipdiff.signature.SignatureManager
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Gradle task for automated build-time patch assembly between base and target archives.
 */
abstract class ZipdiffPatchTask : DefaultTask() {

    @get:InputFile
    @get:Optional
    abstract val baseArchive: RegularFileProperty

    @get:InputFile
    abstract val targetArchive: RegularFileProperty

    @get:Input
    abstract val baseVersion: Property<String>

    @get:Input
    abstract val targetVersion: Property<String>

    @get:Input
    @get:Optional
    abstract val canonicalProfileVersion: Property<String>

    @get:Input
    @get:Optional
    abstract val signatureScheme: Property<String>

    @get:Input
    @get:Optional
    abstract val fallbackOnMissingBase: Property<Boolean>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generatePatch() {
        val baseFile = baseArchive.orNull?.asFile
        val targetFile = targetArchive.get().asFile
        val outDir = outputDirectory.get().asFile

        Files.createDirectories(outDir.toPath())

        val baseExists = baseFile != null && baseFile.exists() && baseFile.isFile
        val shouldFallback = fallbackOnMissingBase.getOrElse(true)

        if (!baseExists) {
            if (shouldFallback) {
                logger.warn("Base archive does not exist at ${baseFile?.absolutePath ?: "unspecified path"}. Falling back to full target archive.")
                val fallbackTarget = outDir.resolve(targetFile.name)
                Files.copy(targetFile.toPath(), fallbackTarget.toPath(), StandardCopyOption.REPLACE_EXISTING)
                return
            } else {
                error("Base archive does not exist at ${baseFile?.absolutePath ?: "unspecified path"} and fallback is disabled.")
            }
        }

        val tempDir = Files.createTempDirectory("zipdiff_task_")
        try {
            val canonicalBase = tempDir.resolve("base_canonical.zip")
            val canonicalTarget = tempDir.resolve("target_canonical.zip")

            val profile = CanonicalProfile()
            val canonicalizer = Canonicalizer()

            val baseResult = canonicalizer.canonicalize(baseFile!!.toPath(), canonicalBase, profile)
            val targetResult = canonicalizer.canonicalize(targetFile.toPath(), canonicalTarget, profile)

            val diffs = DiffGenerator.generateDiff(canonicalBase, canonicalTarget, profile)

            val scheme = signatureScheme.getOrElse("scheme-v1")
            val signatureManager = SignatureManager()
            val signatures = mutableListOf<SignatureBlock>()
            try {
                val sigBlock = signatureManager.generateSignatureBlock(canonicalTarget, scheme)
                signatures.add(sigBlock)
            } catch (e: Exception) {
                logger.warn("Signature block extraction skipped for scheme '$scheme': ${e.message}", e)
            }

            val bVersion = baseVersion.get()
            val tVersion = targetVersion.get()
            val cProfileVer = canonicalProfileVersion.getOrElse("v1")

            val metadata = PatchMetadata(
                baseVersion = bVersion,
                targetVersion = tVersion,
                canonicalizationProfileVersion = cProfileVer,
                canonicalZipSha256 = targetResult.sha256Hex,
                signatureSchemes = if (signatures.isNotEmpty()) signatures.map { it.schemeId } else listOf(scheme)
            )

            val safeBaseVersion = bVersion.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val safeTargetVersion = tVersion.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val patchFileName = "$safeBaseVersion-to-$safeTargetVersion.patch.zp"
            val patchOutputPath = outDir.toPath().resolve(patchFileName)

            PatchPackager.writePatchPackage(
                outputPath = patchOutputPath,
                metadata = metadata,
                diffs = diffs,
                signatures = signatures
            )

            logger.lifecycle("Successfully generated patch package: $patchOutputPath")
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}