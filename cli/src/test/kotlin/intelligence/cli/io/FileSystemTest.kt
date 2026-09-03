package intelligence.cli.io

import java.nio.file.Files
import java.nio.file.attribute.DosFileAttributeView
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse

class FileSystemTest {
    @Test
    fun `delete recursively clears read only files`() {
        val root = Files.createTempDirectory("source-projection-read-only-delete-")
        val readOnlyFile = root.resolve("read-only.txt")
        readOnlyFile.writeText("content\n")
        val dosAttributes = Files.getFileAttributeView(readOnlyFile, DosFileAttributeView::class.java)
        dosAttributes?.setReadOnly(true)

        try {
            FileSystem.deleteRecursively(root)

            assertFalse(root.exists())
        } finally {
            if (root.exists()) {
                dosAttributes?.setReadOnly(false)
                FileSystem.deleteRecursively(root)
            }
        }
    }
}
