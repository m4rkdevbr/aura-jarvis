package app.dzz.copilot.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaManifestTest {
    @Test
    fun readsPlainLines() {
        val names = MediaManifest.parse("IMG_0001.jpg\nVID_0002.mp4\n")
        assertEquals(listOf("IMG_0001.jpg", "VID_0002.mp4"), names)
    }

    @Test
    fun readsJsonNames() {
        val names = MediaManifest.parse("""{"files":[{"filename":"A.jpg"},{"name":"folder/B.mp4"}]}""")
        assertTrue(names.contains("A.jpg"))
        assertTrue(names.contains("folder/B.mp4"))
    }

    @Test
    fun rejectsTraversal() {
        assertEquals(emptyList<String>(), MediaManifest.parse("../secret.jpg"))
    }
}
