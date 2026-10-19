package com.pichs.download.utils

import com.pichs.download.model.PauseReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ErrorClassifierTest {

    @Test
    fun `network exceptions are resumable`() {
        assertTrue(ErrorClassifier.isNetworkError(ConnectException("refused")))
        assertTrue(ErrorClassifier.isNetworkError(SocketTimeoutException("timeout")))
        assertTrue(ErrorClassifier.isNetworkError(UnknownHostException("unknown")))
        assertTrue(ErrorClassifier.isNetworkError(SocketException("closed")))
        assertTrue(ErrorClassifier.isNetworkError(EOFException("eof")))
        assertEquals(
            PauseReason.NETWORK_ERROR,
            ErrorClassifier.getPauseReason(SocketException("connection reset"))
        )
    }

    @Test
    fun `http status errors are terminal`() {
        val error = IOException("HTTP 404")
        assertFalse(ErrorClassifier.isNetworkError(error))
        assertEquals(null, ErrorClassifier.getPauseReason(error))
    }

    @Test
    fun `premature eof is treated as network`() {
        val error = IOException("unexpected end of stream: chunk=1, got=10, expected=100")
        assertEquals(PauseReason.NETWORK_ERROR, ErrorClassifier.getPauseReason(error))
    }

    @Test
    fun `storage errors map to storage full`() {
        val error = IOException("No space left on device")
        assertEquals(PauseReason.STORAGE_FULL, ErrorClassifier.getPauseReason(error))
    }

    @Test
    fun `integrity errors are not resumable`() {
        val error = IOException("最终文件大小不匹配: 10/100")
        assertEquals(null, ErrorClassifier.getPauseReason(error))
    }
}
