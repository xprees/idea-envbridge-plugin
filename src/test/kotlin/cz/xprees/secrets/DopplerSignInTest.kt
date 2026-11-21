package cz.xprees.secrets

import cz.xprees.secrets.doppler.DopplerApi
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class DopplerSignInTest {
    @Test
    fun `sign-in sends a CLI release version`() {
        // Doppler answers anything else with HTTP 400 "Invalid CLI version"
        assertThat(DopplerApi.CLI_VERSION).matches("v\\d+\\.\\d+\\.\\d+")
    }
}
