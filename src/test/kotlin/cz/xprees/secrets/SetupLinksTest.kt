package cz.xprees.secrets

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.xprees.secrets.core.provider.SecretProvider
import cz.xprees.secrets.ui.settings.ConnectionDialog

class SetupLinksTest : BasePlatformTestCase() {
    fun `test only web pages are opened`() {
        assertTrue(ConnectionDialog.isWebPage("https://dashboard.doppler.com"))
        assertTrue(ConnectionDialog.isWebPage("http://localhost:8200/ui/"))
        assertFalse(ConnectionDialog.isWebPage("http://vault.example.com/ui/"))
        assertFalse(ConnectionDialog.isWebPage("file:///etc/passwd"))
        assertFalse(ConnectionDialog.isWebPage("javascript:alert(1)"))
        assertFalse(ConnectionDialog.isWebPage("not a url"))
    }

    fun `test token methods link to where their credentials are made`() {
        val links = SecretProvider.all().flatMap { p -> p.authMethods.map { "${p.id}/${it.id}" to it.setupLinks } }.toMap()
        for (method in listOf("doppler/token", "infisical/machine-identity", "vault/token", "vault/approle", "onepassword/service-account", "onepassword/connect")) {
            assertTrue(method, links[method].orEmpty().isNotEmpty())
        }
        val doppler = links.getValue("doppler/token").first().url("https://api.doppler.com/v3/workplace")
        assertEquals("https://dashboard.doppler.com", doppler)
        assertEquals("https://vault.example.com/ui/", links.getValue("vault/token").first().url("https://vault.example.com/"))
        // Every page is a web page for a typical instance
        links.values.flatten().forEach { link -> assertTrue(link.text, ConnectionDialog.isWebPage(link.url("https://secrets.example.com")!!)) }
    }
}
