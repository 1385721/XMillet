package io.github.univers629.millet.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.time.LocalDateTime
import java.util.zip.GZIPInputStream

class BugreportHelperTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `bugreport and backup filenames match expected timestamp pattern`() {
        val ts = BugreportHelper.formatTimestamp(LocalDateTime.of(2026, 11, 19, 9, 35, 31))
        assertEquals("20261119-093531", ts)
        assertEquals("bugreport-20261119-093531.tar.gz", BugreportHelper.buildBugreportFileName(ts))
        assertEquals("XMillet_Backup_20261119-093531.json", BugreportHelper.buildBackupFileName(ts))
    }

    @Test
    fun `writeTarGz writes valid ustar archive with exact contents and block alignment`() {
        val srcDir = tempFolder.newFolder("src")
        val f1 = File(srcDir, "basic.txt").apply { writeText("hello-xmillet\nline2", Charsets.UTF_8) }
        val f2 = File(srcDir, "xmillet_log.txt").apply { writeText("W log entry ".repeat(120), Charsets.UTF_8) }
        val outTarGz = File(tempFolder.root, "bugreport-20261119-093531.tar.gz")

        BugreportHelper.writeTarGz(listOf(f1, f2), outTarGz)

        val tarBytes = GZIPInputStream(FileInputStream(outTarGz)).use { it.readBytes() }
        assertTrue(tarBytes.size % 512 == 0)

        val decoded = LinkedHashMap<String, String>()
        var offset = 0
        while (offset + 512 <= tarBytes.size) {
            val header = tarBytes.copyOfRange(offset, offset + 512)
            offset += 512
            if (header.all { it == 0.toByte() }) {
                break
            }
            val name = header.copyOfRange(0, 100).takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8)
            val magic = header.copyOfRange(257, 262).toString(Charsets.US_ASCII)
            assertEquals("ustar", magic)

            val sizeOctal = header.copyOfRange(124, 136)
                .takeWhile { it != 0.toByte() && it != ' '.code.toByte() }
                .toByteArray()
                .toString(Charsets.US_ASCII)
                .trim()
            val size = sizeOctal.toInt(8)
            val content = tarBytes.copyOfRange(offset, offset + size).toString(Charsets.UTF_8)
            decoded[name] = content

            val blocks = (size + 511) / 512
            offset += blocks * 512
        }

        assertEquals(f1.readText(Charsets.UTF_8), decoded["basic.txt"])
        assertEquals(f2.readText(Charsets.UTF_8), decoded["xmillet_log.txt"])
    }
}
