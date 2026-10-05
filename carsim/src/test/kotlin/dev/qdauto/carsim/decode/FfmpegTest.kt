package dev.qdauto.carsim.decode

import dev.qdauto.carsim.h264.SyntheticH264
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FfmpegTest {
    @Test
    fun parsesProgressAndErrorLines() {
        assertEquals(312, Ffmpeg.parseFrames("frame=10\nfps=0.0\nprogress=continue\nframe=312\nprogress=end\n"))
        assertNull(Ffmpeg.parseFrames(""))
        assertNull(Ffmpeg.parseFrames("frame=abc"))
        assertEquals(
            listOf("[h264 @ 0x1] non-existing PPS 0 referenced", "[h264 @ 0x1] decode_slice_header error"),
            Ffmpeg.errorLines("[h264 @ 0x1] non-existing PPS 0 referenced\r\n\n[h264 @ 0x1] decode_slice_header error  \n\n"),
        )
        assertTrue(Ffmpeg.errorLines("\n  \n").isEmpty())
    }

    @Test
    fun locatesByExplicitPathDefaultOrPath() {
        val dir = File(System.getProperty("java.io.tmpdir"), "carsim-ffmpeg-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            val missing = File(dir, "nope.exe")
            val fake = File(dir, if (File.separatorChar == '\\') "ffmpeg.exe" else "ffmpeg").apply { writeText("") }
            // Explícito: solo ese.
            assertEquals(fake, Ffmpeg.locate(fake, path = "", default = missing))
            assertNull(Ffmpeg.locate(missing, path = dir.path, default = fake))
            // Por defecto el de tools/ffmpeg...
            assertEquals(fake, Ffmpeg.locate(null, path = "", default = fake))
            // ...y si no, el PATH.
            assertEquals(fake, Ffmpeg.locate(null, path = listOf("C:\\no\\existe", dir.path).joinToString(File.pathSeparator), default = missing))
            assertNull(Ffmpeg.locate(null, path = "C:\\no\\existe", default = missing))
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Con el ffmpeg de tools/: los frames falsos del autotest tienen que dar errores de decodificación. */
    @Test
    fun realFfmpegReportsDecoderErrorsOnFakeFrames() {
        val ffmpeg = Ffmpeg.locate() ?: return // sin ffmpeg no se puede probar
        val file = File.createTempFile("carsim-fake", ".h264")
        try {
            file.outputStream().use { out ->
                out.write(SyntheticH264.codecConfig(640, 360, 30))
                for (i in 0 until 10) out.write(SyntheticH264.fakeFrame(i, i == 0, 3_000))
            }
            val r = Ffmpeg.decode(ffmpeg, file, timeoutMs = 60_000)
            assertNull(r.failure, r.failure)
            assertNotNull(r.exitCode)
            assertFalse(r.ok, "los frames falsos no se pueden decodificar: $r")
            assertTrue(r.errorLines.isNotEmpty(), "sin líneas de error: $r")
        } finally {
            file.delete()
        }
    }
}
