package io.cognotik.zipdiff.patch

import io.cognotik.zipdiff.exception.ZipdiffException
import io.cognotik.zipdiff.`package`.PatchPackage
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Engine for sequentially applying a chain of patch packages to a base ZIP archive.
 */
class PatchChainApplier(
    private val patchApplier: PatchApplier = PatchApplier()
) {
    private val logger = Logger.getLogger(PatchChainApplier::class.java.name)


    /**
     * Reconstructs the final target canonical ZIP archive by sequentially applying a list of [patchPackages]
     * starting from [baseZip].
     *
     * @param baseZip Path to the initial base ZIP archive.
     * @param patchPackages Ordered list of patch packages to apply sequentially.
     * @param outputPath Target path where the final reconstructed canonical ZIP archive will be created.
     * @return Path to the created canonical target ZIP archive.
     * @throws ZipdiffException If hash mismatch occurs or patch application fails at any step in the chain.
     */
    fun applyChain(
        baseZip: Path,
        patchPackages: List<PatchPackage>,
        outputPath: Path
    ): Path {
        if (patchPackages.isEmpty()) {
            throw ZipdiffException("Patch package chain cannot be empty")
        }
        validatePaths(baseZip, outputPath)


        val tempFiles = mutableListOf<Path>()
        try {
            var currentBase = baseZip

            for ((index, patchPackage) in patchPackages.withIndex()) {
                val isLast = index == patchPackages.lastIndex
                val currentTarget = if (isLast) {
                    outputPath
                } else {
                    val tempFile = Files.createTempFile("zipdiff-chain-step-$index-", ".zip")
                    tempFiles.add(tempFile)
                    tempFile
                }

                patchApplier.applyPatch(currentBase, patchPackage, currentTarget)
                currentBase = currentTarget
            }

            return outputPath
        } catch (e: ZipdiffException) {
            throw e
        } catch (e: Exception) {
            throw ZipdiffException("Failed to apply patch chain to $baseZip", e)
        } finally {
            for (tempFile in tempFiles) {
                try {
                    Files.deleteIfExists(tempFile)
                } catch (e: Exception) {
                    logger.log(Level.WARNING, "Failed to delete temporary file: $tempFile", e)
                }
            }
        }
    }
    private fun validatePaths(baseZip: Path, outputPath: Path) {
        val isSame = try {
            Files.isSameFile(baseZip, outputPath)
        } catch (_: Exception) {
            baseZip.toAbsolutePath().normalize() == outputPath.toAbsolutePath().normalize()
        }
        if (isSame) {
            throw ZipdiffException("Base ZIP and output ZIP cannot be the same file: $baseZip")
        }
    }


    companion object {
        /**
         * Convenience method to apply a patch chain using a default [PatchChainApplier] instance.
         */
        fun applyChain(
            baseZip: Path,
            patchPackages: List<PatchPackage>,
            outputPath: Path
        ): Path = PatchChainApplier().applyChain(baseZip, patchPackages, outputPath)
    }
}