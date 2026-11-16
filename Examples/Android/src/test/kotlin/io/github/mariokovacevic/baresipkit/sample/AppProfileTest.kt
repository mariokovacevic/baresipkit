package io.github.mariokovacevic.baresipkit.sample

import io.github.mariokovacevic.baresipkit.BaresipConfiguration
import io.github.mariokovacevic.baresipkit.sample.model.AppProfile
import io.github.mariokovacevic.baresipkit.sample.model.DefaultAppProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.UUID

class AppProfileTest {
    @Test
    fun defaultProfileChangesNothing() {
        val configuration = BaresipConfiguration(UUID.fromString("00000000-0000-4000-8000-000000020f00")) { userAgent = "BaresipKitSample/1.0" }
        assertSame(configuration, DefaultAppProfile.configure(configuration))
        assertNull(DefaultAppProfile.accountSection())
    }

    @Test
    fun loadsTheLocalProfileWhenOneIsCompiledIn() {
        val local = runCatching { Class.forName(AppProfile.LOCAL_CLASS) }.isSuccess
        val profile = AppProfile.load()
        assertEquals(local, profile !== DefaultAppProfile)
    }
}
