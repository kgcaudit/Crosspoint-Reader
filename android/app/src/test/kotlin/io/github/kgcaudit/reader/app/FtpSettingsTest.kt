package io.github.kgcaudit.reader.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.data.sync.FtpConfig
import io.github.kgcaudit.reader.data.sync.FtpEncoding
import io.github.kgcaudit.reader.data.sync.FtpProtocol
import io.github.kgcaudit.reader.data.sync.FtpSecurity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 서버 설정 저장(0.58.0 보강): 새 칸이 그대로 돌아오는가, 첫 0.58.0 이 남긴 설정을 그 뜻대로 읽는가. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FtpSettingsTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val plain = object : SecretBox {
        override fun seal(plain: String) = "sealed:$plain"
        override fun open(sealed: String) = sealed.removePrefix("sealed:").takeIf { sealed.startsWith("sealed:") }
    }
    private val settings = FtpSettings(app) { plain }
    private val prefs = app.getSharedPreferences("sync_ftp", Context.MODE_PRIVATE)

    @Test
    fun `every setting comes back as saved and the password is never stored as typed`() {
        val config = FtpConfig(
            host = "nas.local", port = 2222, protocol = FtpProtocol.Sftp, security = FtpSecurity.None, user = "reader",
            password = "책갈피", path = "/OLO Ecosystem", passive = false, encoding = FtpEncoding.EucKr, trusted = "AB:CD",
        )
        settings.save(config)
        assertEquals(config, settings.config())
        assertEquals("sealed:책갈피", prefs.getString("password", null))
        // 지문을 지우면(다른 서버로 바꿈) 남지 않는다.
        settings.save(config.copy(trusted = null))
        assertNull(settings.config()?.trusted)
    }

    @Test
    fun `settings left by the first 0_58 release keep their encryption choice and the old folder`() {
        // 첫 0.58.0 은 secure 켬 · 끔만 적었다. 끔을 "자동" 으로 읽으면, 스스로 만든 인증서가 있는 NAS 에서 잘 되던 맞추기가
        // 인증서 물음 없이(설정 화면 밖이라) 멈춘다.
        prefs.edit().putString("host", "nas.local").putInt("port", 21).putBoolean("secure", false).putString("user", "u")
            .putString("password", "sealed:p").putString("path", "/OLO").commit()
        val old = settings.config()!!
        assertEquals(FtpSecurity.None, old.security)
        assertEquals(FtpProtocol.Ftp, old.protocol)
        assertEquals("/OLO", old.path)
        assertEquals(true, old.passive)
        prefs.edit().putBoolean("secure", true).commit()
        assertEquals(FtpSecurity.Explicit, settings.config()!!.security)
        // 다음 판이 더한 모르는 값은 처음 값으로 — 설정을 통째로 잃지 않는다.
        prefs.edit().putString("security", "Quantum").putString("protocol", "Webdav").commit()
        assertEquals(FtpSecurity.Explicit, settings.config()!!.security)
        assertEquals(FtpProtocol.Ftp, settings.config()!!.protocol)
        // 비밀번호를 열지 못하면(키가 바뀜) 맞추지 않는다.
        prefs.edit().putString("password", "garbage").commit()
        assertNull(settings.config())
    }
}
