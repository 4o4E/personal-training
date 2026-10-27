package top.e404.training

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import top.e404.training.shared.wireJson

@Serializable enum class Appearance(val title: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }
@Serializable data class DeviceChoices(
    val appearance: Appearance = Appearance.SYSTEM,
    val guide: String? = null,
    // 仅用于将升级前的本机业务选择迁移到云端；确认迁移成功后立即清除。
    val programId: String? = null,
    val slotPlans: Map<String, Map<String, String>> = emptyMap()
)

/** 本机交互偏好不进入训练资料、开放接口或备份；连接凭据仍由 ConnectionVault 独立管理。 */
class AppPreferences(context: Context) {
    private val storage = context.getSharedPreferences("interaction", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(storage.getString("choices", null)?.let { wireJson.decodeFromString<DeviceChoices>(it) } ?: DeviceChoices())
    val data = mutable.asStateFlow()
    private fun save(value: DeviceChoices) {
        storage.edit().putString("choices", wireJson.encodeToString(value)).apply()
        mutable.value = value
    }
    fun appearance(value: Appearance) = save(mutable.value.copy(appearance = value))
    fun finishGuide(status: String) = save(mutable.value.copy(guide = status))
    fun legacySelection(): Pair<String?, Map<String, Map<String, String>>> =
        mutable.value.programId to mutable.value.slotPlans
    fun clearLegacyChoices() = save(mutable.value.copy(programId = null, slotPlans = emptyMap()))
}
