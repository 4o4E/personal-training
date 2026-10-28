package top.e404.training

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable fun FirstUseGuide(viewModel: TrainingViewModel, onSkip: () -> Unit, onFinish: (Boolean) -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val connection = remember { viewModel.repository.vault.load() }
    var url by rememberSaveable { mutableStateOf(connection.url) }
    // 与设置页一致：token 仅留内存草稿，确认后交由系统保护的凭据存储管理。
    var token by remember { mutableStateOf(connection.token) }
    var saving by remember { mutableStateOf(false) }
    FormDialog("开始使用 · ${step + 1}/4", onSkip, dismissLabel = "跳过教程", dismissOnClickOutside = false, scrollKey = step) {
        when (step) {
            0 -> {
                Text("按照列表训练，完成一组后进入组间休息", style = MaterialTheme.typography.headlineSmall)
                InfoCard("开始训练") { Text("进入当日训练页面即可逐组记录。完成一组后按该组要求计时休息；实际训练和拉伸分别统计。") }
                InfoCard("调整计划") { Text("先从三、四、五分化中选择整轮候选，再按日替换动作、修改组数及加入对应拉伸。健身房和器械登记不是必填。") }
                InfoCard("运动记录") { Text("查看实际完成的组、原始 kg/lb 读数和训练反馈；计划组不会自动算作完成组。") }
                Text("左上角菜单包含设置、可选健身房和本教程。动作与拉伸教学就在选择动作、计划预览及训练过程中。")
            }
            1 -> {
                Text("连接自己的后端", style = MaterialTheme.typography.headlineSmall)
                Text("后端保存并同步训练资料，也提供给助手使用的同一套开放接口。应用不注册账号，使用后端地址和唯一 token。")
                TextInput("后端地址", url, { url = it }, keyboardType = KeyboardType.Uri)
                OutlinedTextField(token, { token = it }, label = { Text("唯一 token") }, visualTransformation = PasswordVisualTransformation(), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
                Text("填写完整服务地址，例如 https://training.example.com:3443。token 使用部署后端时配置的同一份，输入后以掩码显示。")
                Text("可以稍后连接：离线也能选择方案、训练、计时和查看本机记录；以后从菜单中的设置连接并同步。")
                Button(enabled = !saving, onClick = {
                    saving = true
                    viewModel.action {
                        try { viewModel.repository.saveConnection(BackendConnection(url, token.trim())); step = 2 }
                        finally { saving = false }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("保存连接") }
                TextButton(onClick = { step = 2 }) { Text(if (connection.token.isBlank()) "稍后连接" else "保留现有连接并继续") }
            }
            2 -> {
                Text("整轮先选一套，再按日调整", style = MaterialTheme.typography.headlineSmall)
                Text("三、四、五分化分别是一轮三、四、五次训练，不等于固定每周频率。完成一次才推进下一项，休息和取消不会跳过部位。")
                Text("每套候选会列出动作顺序、主要和辅助部位、器械条件。动作数量与组数的区别以实际训练量说明，不按效果排名。")
                Text("预览确认后才保存整轮计划；可在预览时选择对应拉伸，也可之后调整单日，不影响其他训练项或已经开始的训练。")
            }
            3 -> {
                Text("休息结束，发送普通通知", style = MaterialTheme.typography.headlineSmall)
                Text("应用到时唤醒自身并发布通知，不调用系统闹钟响铃。通知声音和振动遵循系统及该通知渠道的设置。")
                Text("开始训练时按需请求通知权限。菜单 → 设置可查看通知、定时唤醒和应用后台条件；不需要现在一次性申请全部权限。")
                Text("深浅外观默认跟随系统，也可以从设置固定为浅色或深色。连接配置、教程和外观都可以以后修改。")
                Button(onClick = { onFinish(true) }, modifier = Modifier.fillMaxWidth()) { Text("完成教程，选择训练方案") }
                TextButton(onClick = { onFinish(false) }) { Text("完成教程，进入应用") }
            }
        }
        if (step != 1 && step < 3) Button(onClick = { step++ }, modifier = Modifier.fillMaxWidth()) { Text("继续") }
        if (step > 0) TextButton(onClick = { step-- }) { Text("返回上一步") }
    }
}
