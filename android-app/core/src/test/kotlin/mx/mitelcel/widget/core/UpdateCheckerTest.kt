package mx.mitelcel.widget.core

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class UpdateCheckerTest {

    @Test
    fun `compara versiones número por número, no como texto`() {
        assertTrue(UpdateChecker.isNewer("1.4", "1.3"))
        assertTrue(UpdateChecker.isNewer("v1.10", "1.9")) // como texto, "1.10" < "1.9"
        assertTrue(UpdateChecker.isNewer("2.0", "1.99"))
        assertTrue(UpdateChecker.isNewer("1.3.1", "1.3"))
        assertFalse(UpdateChecker.isNewer("1.3", "1.3"))
        assertFalse(UpdateChecker.isNewer("1.3", "1.3.0"))
        assertFalse(UpdateChecker.isNewer("1.2", "1.3"))
    }

    @Test
    fun `las versiones de prueba van entre la anterior y la final`() {
        assertFalse(UpdateChecker.isNewer("1.4.1", "1.5-beta")) // no ofrecer bajar de versión
        assertTrue(UpdateChecker.isNewer("1.5", "1.5-beta")) // la final sí reemplaza a la beta
        assertTrue(UpdateChecker.isNewer("1.5-beta", "1.4.1"))
        assertFalse(UpdateChecker.isNewer("1.5-beta", "1.5"))
        assertFalse(UpdateChecker.isNewer("1.5-beta", "1.5-beta"))
    }

    @Test
    fun `lee el release y su APK`() {
        val json = """
            {"tag_name":"v1.4","body":"Bait y actualizaciones",
             "assets":[{"name":"otro.zip","browser_download_url":"https://x/otro.zip"},
                       {"name":"CompaniasMxWidget.apk","browser_download_url":"https://x/CompaniasMxWidget.apk"}]}
        """.trimIndent()

        assertEquals(
            ReleaseInfo("1.4", "https://x/CompaniasMxWidget.apk", "Bait y actualizaciones"),
            UpdateChecker.parseRelease(json),
        )
        // Un release sin el APK (ej. aún compilándose) no se ofrece como actualización.
        assertNull(UpdateChecker.parseRelease("""{"tag_name":"v1.4","assets":[]}"""))
    }

    @Test
    fun `solo ofrece la actualización si es más nueva y la descarga completa`() {
        val server = MockWebServer()
        server.start() // antes de url(): url() arranca el servidor por su cuenta y start() fallaría después
        val apkUrl = server.url("/CompaniasMxWidget.apk").toString()
        val release = """{"tag_name":"v1.4","body":"","assets":[{"name":"${UpdateChecker.APK_NAME}","browser_download_url":"$apkUrl"}]}"""
        server.enqueue(MockResponse().setBody(release))
        server.enqueue(MockResponse().setBody(release))
        server.enqueue(MockResponse().setBody("APK-FALSO"))
        try {
            val checker = UpdateChecker(latestReleaseUrl = server.url("/latest").toString())

            assertNull(checker.findUpdate("1.4"))
            val update = checker.findUpdate("1.3")!!
            assertEquals("1.4", update.version)

            val target = Files.createTempDirectory("upd").resolve(UpdateChecker.APK_NAME).toFile()
            checker.download(update, target)
            assertEquals("APK-FALSO", target.readText())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `sin releases no es un error`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404))
        server.start()
        try {
            assertNull(UpdateChecker(latestReleaseUrl = server.url("/latest").toString()).findUpdate("1.3"))
        } finally {
            server.shutdown()
        }
    }
}
