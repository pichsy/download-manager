package com.pichs.download.utils

import com.pichs.download.config.Checksum
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChecksumVerifierTest {

    private val file = File.createTempFile("checksum-test", ".bin")

    @After
    fun tearDown() {
        file.delete()
    }

    @Test
    fun `computes known md5 and sha256`() {
        file.writeText("abc")

        assertEquals(
            "900150983cd24fb0d6963f7d28e17f72",
            ChecksumVerifier.compute(file, Checksum.Type.MD5)
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ChecksumVerifier.compute(file, Checksum.Type.SHA256)
        )
    }

    @Test
    fun `verify is case insensitive and accepts algorithm prefix`() {
        file.writeText("abc")

        assertTrue(
            ChecksumVerifier.verify(
                file,
                Checksum(Checksum.Type.MD5, "900150983CD24FB0D6963F7D28E17F72")
            )
        )
        assertTrue(
            ChecksumVerifier.verify(
                file,
                Checksum(Checksum.Type.SHA256, "sha256:BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD")
            )
        )
    }

    @Test
    fun `verify rejects mismatched value`() {
        file.writeText("abc")

        assertFalse(
            ChecksumVerifier.verify(
                file,
                Checksum(Checksum.Type.MD5, "00000000000000000000000000000000")
            )
        )
    }

    @Test
    fun `verify rejects missing file`() {
        file.delete()

        assertFalse(
            ChecksumVerifier.verify(
                file,
                Checksum(Checksum.Type.MD5, "900150983cd24fb0d6963f7d28e17f72")
            )
        )
    }

    @Test
    fun `normalizes whitespace prefix and case`() {
        assertEquals(
            "900150983cd24fb0d6963f7d28e17f72",
            ChecksumVerifier.normalizeExpected("  MD5:900150983CD24FB0D6963F7D28E17F72 ")
        )
        assertEquals(
            "abc",
            ChecksumVerifier.normalizeExpected("0xABC")
        )
    }
}
