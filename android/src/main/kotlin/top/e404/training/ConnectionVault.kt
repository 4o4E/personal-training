package top.e404.training

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class BackendConnection(val url: String, val token: String) {
    val configured: Boolean get() = url.isNotBlank() && token.isNotBlank()
}

/** token 只存入 Android Keystore 加密的设备配置，不进入业务数据库或导出包。 */
class ConnectionVault(context: Context) {
    private val preferences = context.getSharedPreferences("backend_connection", Context.MODE_PRIVATE)
    private val alias = "personal-training-backend"
    private var cached: BackendConnection? = null
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        if (store.containsAlias(alias)) return store.getKey(alias, null) as SecretKey
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun load(): BackendConnection {
        cached?.let { return it }
        val encrypted = preferences.getString("token", null) ?: return BackendConnection("", "").also { cached = it }
        val iv = Base64.decode(preferences.getString("iv", null)!!, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        return BackendConnection(preferences.getString("url", "")!!, cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)).toString(Charsets.UTF_8)).also { cached = it }
    }
    @Synchronized fun save(connection: BackendConnection) {
        val uri = java.net.URI.create(connection.url.trim())
        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()) { "请填写有效的 HTTP 或 HTTPS 后端地址" }
        require(connection.token.isNotBlank()) { "请填写连接 token" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(connection.token.toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString("url", connection.url.trim().trimEnd('/'))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("token", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) { "无法保存后端连接" }
        cached = BackendConnection(connection.url.trim().trimEnd('/'), connection.token)
    }
    @Synchronized fun clear() { check(preferences.edit().clear().commit()) { "无法清除后端连接" }; cached = BackendConnection("", "") }
}
