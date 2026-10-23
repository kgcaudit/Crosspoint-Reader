package io.github.kgcaudit.reader.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.kgcaudit.reader.data.sync.FtpConfig
import io.github.kgcaudit.reader.data.sync.FtpEncoding
import io.github.kgcaudit.reader.data.sync.FtpProtocol
import io.github.kgcaudit.reader.data.sync.FtpSecurity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 비밀번호를 감추는 상자. 시험은 감추지 않는 상자를 꽂는다(Robolectric 에는 안드로이드 키 저장소가 없다). */
internal interface SecretBox {
    fun seal(plain: String): String
    fun open(sealed: String): String?
}

/**
 * 안드로이드 키 저장소의 AES-GCM 키로 감춘다. 키는 휴대폰 밖으로 나오지 않는다 — 앱 데이터를 통째로 빼 가도(백업 · 루팅) 비밀번호는
 * 읽히지 않는다. 앱을 지우면 키도 사라진다.
 */
internal class KeystoreBox : SecretBox {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(STORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE).apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    override fun seal(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(body, Base64.NO_WRAP)
    }

    /** 못 열면 null — 키가 바뀌었다(앱을 다시 깔고 설정만 복원됨). 사람에게 비밀번호를 다시 묻는다. */
    override fun open(sealed: String): String? = runCatching {
        val (iv, body) = sealed.split(':', limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        String(cipher.doFinal(body), Charsets.UTF_8)
    }.getOrNull()

    private companion object {
        const val STORE = "AndroidKeyStore"
        const val ALIAS = "olo-sync-ftp"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}

/**
 * 이어 읽기의 FTP 서버 설정(0.58.0). 비밀번호는 [box] 로 감춰 둔다. 설정 파일은 앱 백업에서 빠지는 곳에 둔다 — 새 휴대폰으로
 * 옮겨져도 키가 없어 비밀번호를 열지 못하고, 기기 id 처럼 두 기기가 같은 설정을 갖는 일도 없다.
 */
internal class FtpSettings(context: Context, private val box: () -> SecretBox) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 저장된 설정. 주소가 없거나 비밀번호를 열지 못하면 null(맞추지 않는다). */
    fun config(): FtpConfig? {
        val host = prefs.getString(HOST, null)?.takeIf { it.isNotBlank() } ?: return null
        val password = prefs.getString(PASSWORD, null)?.let { box().open(it) } ?: return null
        return FtpConfig(
            host = host,
            port = prefs.getInt(PORT, FtpConfig.DEFAULT_PORT),
            protocol = enumOr(prefs.getString(PROTOCOL, null), FtpProtocol.Ftp),
            // 0.58.0 첫 판은 켬 · 끔(secure)만 적었다. 끔이던 서버를 자동으로 바꾸면, 스스로 만든 인증서가 있는 NAS 에서 잘 되던
            // 맞추기가 "인증서를 믿을 수 없음" 으로 멈춘다 — 적힌 대로 옮긴다.
            security = enumOr(prefs.getString(SECURITY, null), if (prefs.getBoolean(SECURE, false)) FtpSecurity.Explicit else FtpSecurity.None),
            user = prefs.getString(USER, "").orEmpty(),
            password = password,
            path = prefs.getString(PATH, DEFAULT_PATH).orEmpty(),
            passive = prefs.getBoolean(PASSIVE, true),
            encoding = enumOr(prefs.getString(ENCODING, null), FtpEncoding.Auto),
            trusted = prefs.getString(TRUSTED, null),
        )
    }

    fun save(config: FtpConfig) {
        prefs.edit()
            .putString(HOST, config.host.trim())
            .putInt(PORT, config.port)
            .putString(PROTOCOL, config.protocol.name)
            .putString(SECURITY, config.security.name)
            .remove(SECURE)
            .putString(USER, config.user.trim())
            .putString(PASSWORD, box().seal(config.password))
            .putString(PATH, config.path.trim())
            .putBoolean(PASSIVE, config.passive)
            .putString(ENCODING, config.encoding.name)
            .apply { if (config.trusted != null) putString(TRUSTED, config.trusted) else remove(TRUSTED) }
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    /** 모르는 이름(다음 판이 더한 값을 옛 판이 읽음)이면 처음 값. */
    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: fallback

    companion object {
        private const val PREFS = "sync_ftp"
        private const val HOST = "host"
        private const val PORT = "port"
        private const val SECURE = "secure"
        private const val USER = "user"
        private const val PASSWORD = "password"
        private const val PATH = "path"
        private const val PROTOCOL = "protocol"
        private const val SECURITY = "security"
        private const val PASSIVE = "passive"
        private const val ENCODING = "encoding"
        private const val TRUSTED = "trusted"

        /** 저장 폴더의 처음 값(사용자 결정). 서버 맨 위에 여러 앱의 파일이 섞이지 않게 OLO 앱들이 함께 쓸 제 폴더를 둔다. */
        const val DEFAULT_PATH = "/OLO Ecosystem"
    }
}
