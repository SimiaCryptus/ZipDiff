package io.cognotik.zipdiff.signature

import io.cognotik.zipdiff.exception.SignatureValidationException
import io.cognotik.zipdiff.testutil.TestZipUtils
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SignatureManagerTest {

    private val manager = SignatureManager()

    @Test
    fun `generateSignatureBlock extracts META_INF_ENTRY signature with explicit metadata`(@TempDir tempDir: Path) {
        val zip = tempDir.resolve("test.zip")
        val sigBytes = "signature-bytes".toByteArray()
        val metadataJson = """{"schemeId":"sigA","canonicalProfileVersion":"2.0","placementRule":"META_INF_ENTRY","targetZipHash":"customHash123"}"""
        TestZipUtils.createZip(
            zip,
            mapOf(
                "META-INF/signature-schemes.json" to metadataJson.toByteArray(),
                "META-INF/sigA.sig" to sigBytes
            )
        )

        val block = manager.generateSignatureBlock(zip, "sigA")

        assertEquals("sigA", block.schemeId)
        assertEquals("2.0", block.canonicalProfileVersion)
        assertEquals("customHash123", block.targetZipHash)
        assertEquals(PlacementRule.META_INF_ENTRY, block.placementRule)
        assertArrayEquals(sigBytes, block.signatureData)
    }

    @Test
    fun `generateSignatureBlock falls back to defaults without metadata`(@TempDir tempDir: Path) {
        val zip = tempDir.resolve("test.zip")
        val sigBytes = "raw-sig".toByteArray()
        TestZipUtils.createZip(zip, mapOf("META-INF/schemeX.sig" to sigBytes))

        val block = manager.generateSignatureBlock(zip, "schemeX")

        assertEquals("1.0", block.canonicalProfileVersion)
        assertEquals(PlacementRule.META_INF_ENTRY, block.placementRule)
        assertArrayEquals(sigBytes, block.signatureData)
        assertEquals(TestZipUtils.sha256(zip), block.targetZipHash)
    }

    @Test
    fun `generateSignatureBlock extracts CENTRAL_DIRECTORY_COMMENT signature`(@TempDir tempDir: Path) {
        val zip = tempDir.resolve("test.zip")
        val metadataJson = """{"schemeId":"sigComment","canonicalProfileVersion":"1.0","placementRule":"CENTRAL_DIRECTORY_COMMENT"}"""
        TestZipUtils.createZip(
            zip,
            mapOf("META-INF/signature-schemes.json" to metadataJson.toByteArray()),
            comment = "the-signature-comment"
        )

        val block = manager.generateSignatureBlock(zip, "sigComment")

        assertEquals(PlacementRule.CENTRAL_DIRECTORY_COMMENT, block.placementRule)
        assertEquals("the-signature-comment", String(block.signatureData))
    }

    @Test
    fun `generateSignatureBlock extracts EXTRA_FIELD signature`(@TempDir tempDir: Path) {
        val zip = tempDir.resolve("test.zip")
        val sigBytes = "extra-field-data".toByteArray()
        val metadataJson = """{"schemeId":"sigExtra","canonicalProfileVersion":"1.0","placementRule":"EXTRA_FIELD"}"""
        TestZipUtils.createZip(
            zip,
            mapOf(
                "META-INF/signature-schemes.json" to metadataJson.toByteArray(),
                "META-INF/sigExtra.extra" to sigBytes
            )
        )

        val block = manager.generateSignatureBlock(zip, "sigExtra")

        assertEquals(PlacementRule.EXTRA_FIELD, block.placementRule)
        assertArrayEquals(sigBytes, block.signatureData)
    }

    @Test
    fun `generateSignatureBlock extracts DEDICATED_SECTION signature`(@TempDir tempDir: Path) {
        val zip = tempDir.resolve("test.zip")
        val sigBytes = "dedicated-section-data".toByteArray()
        val metadataJson = """{"schemeId":"sigDed","canonicalProfileVersion":"1.0","placementRule":"DEDICATED_SECTION"}"""
        TestZipUtils.createZip(
            zip,
            mapOf(
                "META-INF/signature-schemes.json" to metadataJson.toByteArray(),
                "META-INF/sigDed.dedicated" to sigBytes
            )
        )

        val block = manager.generateSignatureBlock(zip, "sigDed")

        assertEquals(PlacementRule.DEDICATED_SECTION, block.placementRule)
        assertArrayEquals(sigBytes, block.signatureData)
    }

    @Test
    fun `generateSignatureBlock selects matching scheme from schemes list metadata`(@TempDir tempDir: Path) {
        val zip = tempDir.resolve("test.zip")
        val sigBytes = "list-sig-data".toByteArray()
        val metadataJson = """{"version":"1.0","schemes":[{"schemeId":"other","canonicalProfileVersion":"0.9","placementRule":"META_INF_ENTRY"},{"schemeId":"target","canonicalProfileVersion":"3.0","placementRule":"META_INF_ENTRY","targetZipHash":"listHash"}]}"""
        TestZipUtils.createZip(
            zip,
            mapOf(
                "META-INF/signature-schemes.json" to metadataJson.toByteArray(),
                "META-INF/target.sig" to sigBytes
            )
        )

        val block = manager.generateSignatureBlock(zip, "target")

        assertEquals("3.0", block.canonicalProfileVersion)
        assertEquals("listHash", block.targetZipHash)
        assertArrayEquals(sigBytes, block.signatureData)
    }

    @Test
    fun `generateSignatureBlock throws when signature data missing`(@TempDir tempDir: Path) {
        val zip = tempDir.resolve("test.zip")
        TestZipUtils.createZip(zip, mapOf("some/file.txt" to "content".toByteArray()))

        assertThrows(IllegalArgumentException::class.java) {
            manager.generateSignatureBlock(zip, "missingScheme")
        }
    }

    @Test
    fun `applySignatureBlock throws on hash mismatch`(@TempDir tempDir: Path) {
        val zip = tempDir.resolve("test.zip")
        TestZipUtils.createZip(zip, mapOf("file.txt" to "hello".toByteArray()))

        val block = SignatureBlock(
            schemeId = "sigA",
            canonicalProfileVersion = "1.0",
            targetZipHash = "0".repeat(64),
            signatureData = "data".toByteArray(),
            placementRule = PlacementRule.META_INF_ENTRY
        )

        assertThrows(SignatureValidationException::class.java) {
            manager.applySignatureBlock(zip, block)
        }
    }

    @Test
    fun `applySignatureBlock writes META_INF_ENTRY signature and metadata`(@TempDir tempDir: Path) {
        val zip = tempDir.resolve("test.zip")
        TestZipUtils.createZip(zip, mapOf("file.txt" to "hello".toByteArray()))
        val correctHash = TestZipUtils.sha256(zip)

        val block = SignatureBlock(
            schemeId = "sigA",
            canonicalProfileVersion = "1.0",
            targetZipHash = correctHash,
            signatureData = "sig-payload".toByteArray(),
            placementRule = PlacementRule.META_INF_ENTRY
        )

        manager.applySignatureBlock(zip, block)

        val sigEntry = TestZipUtils.readZipEntry(zip, "META-INF/sigA.sig")
        assertNotNull(sigEntry)
        assertArrayEquals("sig-payload".toByteArray(), sigEntry)

        val originalFile = TestZipUtils.readZipEntry(zip, "file.txt")
        assertArrayEquals("hello".toByteArray(), originalFile)

        val metadataBytes = TestZipUtils.readZipEntry(zip, "META-INF/signature-schemes.json")
        assertNotNull(metadataBytes)
        val metadataJson = String(metadataBytes!!)
        assertTrue(metadataJson.contains("sigA"))
    }

    @Test
    fun `applySignatureBlock sets zip comment for CENTRAL_DIRECTORY_COMMENT`(@TempDir tempDir: Path) {
        val zip = tempDir.resolve("test.zip")
        TestZipUtils.createZip(zip, mapOf("file.txt" to "hello".toByteArray()))
        val correctHash = TestZipUtils.sha256(zip)

        val block = SignatureBlock(
            schemeId = "sigComment",
            canonicalProfileVersion = "1.0",
            targetZipHash = correctHash,
            signatureData = "my-comment-signature".toByteArray(),
            placementRule = PlacementRule.CENTRAL_DIRECTORY_COMMENT
        )

        manager.applySignatureBlock(zip, block)

        assertEquals("my-comment-signature", TestZipUtils.zipComment(zip))
    }

    @Test
    fun `applySignatureBlock writes EXTRA_FIELD entry`(@TempDir tempDir: Path) {
        val zip = tempDir.resolve("test.zip")
        TestZipUtils.createZip(zip, mapOf("file.txt" to "hello".toByteArray()))
        val correctHash = TestZipUtils.sha256(zip)

        val block = SignatureBlock(
            schemeId = "sigExtra",
            canonicalProfileVersion = "1.0",
            targetZipHash = correctHash,
            signatureData = "extra-data".toByteArray(),
            placementRule = PlacementRule.EXTRA_FIELD
        )

        manager.applySignatureBlock(zip, block)

        val entryBytes = TestZipUtils.readZipEntry(zip, "META-INF/sigExtra.extra")
        assertArrayEquals("extra-data".toByteArray(), entryBytes)
    }

    @Test
    fun `applySignatureBlock preserves existing scheme entries in metadata`(@TempDir tempDir: Path) {
        val zip = tempDir.resolve("test.zip")
        val existingMetadata = """{"version":"1.0","schemes":[{"schemeId":"other","canonicalProfileVersion":"1.0","placementRule":"META_INF_ENTRY"}]}"""
        TestZipUtils.createZip(
            zip,
            mapOf(
                "file.txt" to "hello".toByteArray(),
                "META-INF/signature-schemes.json" to existingMetadata.toByteArray()
            )
        )
        val correctHash = TestZipUtils.sha256(zip)

        val block = SignatureBlock(
            schemeId = "sigNew",
            canonicalProfileVersion = "1.0",
            targetZipHash = correctHash,
            signatureData = "new-sig".toByteArray(),
            placementRule = PlacementRule.META_INF_ENTRY
        )

        manager.applySignatureBlock(zip, block)

        val metadataBytes = TestZipUtils.readZipEntry(zip, "META-INF/signature-schemes.json")
        val metadataJson = String(metadataBytes!!)
        assertTrue(metadataJson.contains("other"))
        assertTrue(metadataJson.contains("sigNew"))
    }
}