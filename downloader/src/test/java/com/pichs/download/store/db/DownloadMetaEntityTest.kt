package com.pichs.download.store.db

import com.pichs.download.config.Checksum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadMetaEntityTest {

    @Test
    fun `checksum survives persistence mapping`() {
        val entity = DownloadMetaEntity(
            taskId = "task",
            headersEncoded = HeaderCodec.encode(mapOf("Authorization" to "Bearer token")),
            checksumType = "SHA256",
            checksumValue = "ABCDEF",
            checksumOnFail = "Retry",
        )

        val meta = entity.toModel()

        assertEquals(Checksum(Checksum.Type.SHA256, "ABCDEF", Checksum.OnFail.Retry), meta.checksum)
        assertEquals(mapOf("Authorization" to "Bearer token"), meta.headers)
    }

    @Test
    fun `unknown checksum type is ignored instead of crashing restore`() {
        val entity = DownloadMetaEntity(
            taskId = "task",
            headersEncoded = "",
            checksumType = "CRC32",
            checksumValue = "1234",
            checksumOnFail = "Fail",
        )

        assertNull(entity.toModel().checksum)
    }

    @Test
    fun `missing checksum value is ignored`() {
        val entity = DownloadMetaEntity(
            taskId = "task",
            headersEncoded = "",
            checksumType = "MD5",
            checksumValue = "",
        )

        assertNull(entity.toModel().checksum)
    }
}
