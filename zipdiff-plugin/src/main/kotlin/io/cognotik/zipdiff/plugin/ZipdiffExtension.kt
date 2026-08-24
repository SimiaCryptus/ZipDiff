package io.cognotik.zipdiff.plugin

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property

/**
 * Configuration extension for the Zipdiff Gradle plugin.
 *
 * Exposes configurable properties for archive paths, versioning, output directories,
 * canonicalization profile settings, signature scheme selection, and fallback configuration.
 */
abstract class ZipdiffExtension {

    /**
     * Path to the base ZIP archive.
     */
    abstract val baseArchive: RegularFileProperty

    /**
     * Path to the target ZIP archive.
     */
    abstract val targetArchive: RegularFileProperty

    /**
     * Version identifier string for the base archive.
     */
    abstract val baseVersion: Property<String>

    /**
     * Version identifier string for the target archive.
     */
    abstract val targetVersion: Property<String>

    /**
     * Directory path where generated patch files and canonical archives will be saved.
     */
    abstract val outputDirectory: DirectoryProperty

    /**
     * Profile version identifier for archive canonicalization rules. Defaults to 'v1'.
     */
    abstract val canonicalProfileVersion: Property<String>

    /**
     * Scheme identifier used for archive signature generation and validation. Defaults to 'scheme-v1'.
     */
    abstract val signatureScheme: Property<String>

    /**
     * Whether to fallback gracefully when the base archive is missing. Defaults to true.
     */
    abstract val fallbackOnMissingBase: Property<Boolean>

    init {
        canonicalProfileVersion.convention("v1")
        signatureScheme.convention("scheme-v1")
        fallbackOnMissingBase.convention(true)
    }
}