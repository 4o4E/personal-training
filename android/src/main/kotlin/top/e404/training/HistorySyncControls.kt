package top.e404.training

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import top.e404.training.shared.*
@Composable
fun HistorySyncControls(
    state: AppData,
    viewModel: TrainingViewModel,
    snackbarHostState: SnackbarHostState,
    openConflicts: () -> Unit
) {
    var detailsOpen by remember { mutableStateOf(false) }
    val manualBusy by viewModel.historySyncInProgress.collectAsStateWithLifecycle()
    val busy = manualBusy || state.syncStatus == SyncStatus.syncing
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val collection = state.health.healthStatus?.collection
    val detailError = state.health.detailStateByDataset.values.any { it.status == HealthDetailStatus.error }
    val problem = state.syncStatus == SyncStatus.error || state.mediaSyncStatus == MediaSyncStatus.error ||
        state.health.healthError != null || state.health.collectionError != null || detailError || state.conflicts.isNotEmpty() ||
        state.pending > 0 || collection?.stage == "failed"

    LaunchedEffect(lifecycleOwner, collection?.stage, state.health.collectionError) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.observeCollectionStatus()
        }
    }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var observed = viewModel.repository.data.value.syncCompletionSequence
            viewModel.repository.data.collect { latest ->
                if (latest.syncCompletionSequence > observed) {
                    observed = latest.syncCompletionSequence
                    if (!viewModel.historySyncInProgress.value &&
                        latest.syncCompletionSequence > viewModel.historySyncHandledSequence.value) {
                        val result = historyBackgroundSyncResult(latest)
                        val snackbar = snackbarHostState.showSnackbar(
                            result.message,
                            actionLabel = if (result.successful) null else "查看原因",
                            duration = SnackbarDuration.Short
                        )
                        if (snackbar == SnackbarResult.ActionPerformed) detailsOpen = true
                    }
                }
            }
        }
    }

    fun retry() {
        if (busy) return
        val started = viewModel.retryAllForHistory { result ->
            scope.launch {
                val snackbar = snackbarHostState.showSnackbar(
                    result.message,
                    actionLabel = if (result.successful) null else "查看原因",
                    duration = SnackbarDuration.Short
                )
                if (snackbar == SnackbarResult.ActionPerformed) detailsOpen = true
            }
        }
        if (!started) return
    }

    Box(
        Modifier.size(48.dp).semantics {
            contentDescription = if (busy) "同步中" else if (problem) "同步问题与重试，长按查看详情" else "立即同步，长按查看详情"
        }.combinedClickable(
            onClick = { if (busy) Unit else if (problem) detailsOpen = true else retry() },
            onLongClick = { detailsOpen = true },
            enabled = !busy
        ),
        contentAlignment = Alignment.Center
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        else Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }

    if (detailsOpen) {
        AlertDialog(
            onDismissRequest = { detailsOpen = false },
            title = { Text("同步详情") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text("训练资料：${state.syncStatus.historyLabel()} · ${state.syncMessage}")
                    Text("最近成功：${state.lastSuccessfulSyncAt?.let(::localDateTime) ?: "尚无"} · 待上传 ${state.pending} 项")
                    Text("素材：${state.mediaSyncStatus.historyLabel()}")
                    Text("健康下载${state.health.selectedDate?.let { "（$it）" } ?: ""}：${state.health.healthError?.let { "失败：$it" } ?: "状态见下方详情"}")
                    if (state.health.detailStateByDataset.isEmpty()) Text("健康详情：未请求")
                    state.health.detailStateByDataset.forEach { (dataset, detail) ->
                        Text("${dataset.historyLabel()}：${detail.status.historyLabel()}${detail.error?.let { " · $it" } ?: ""}")
                    }
                    state.health.collectionError?.let { Text("小米采集请求失败：$it", color = MaterialTheme.colorScheme.error) }
                    collection?.let {
                        Text("小米采集：${it.stage.historyLabel()}${it.phase?.let { phase -> " · ${phase.historyLabel()}" } ?: ""}${it.error?.let { error -> " · $error" } ?: ""}")
                    } ?: Text("小米采集：状态未知")
                    if (state.conflicts.isNotEmpty()) Text("待处理冲突：${state.conflicts.size} 项")
                    if (state.conflicts.isNotEmpty()) TextButton(onClick = { detailsOpen = false; openConflicts() }) { Text("处理冲突") }
                    TextButton(onClick = { viewModel.requestCollection(); detailsOpen = false }) { Text("重新请求小米采集") }
                }
            },
            confirmButton = { TextButton(onClick = { detailsOpen = false; retry() }) { Text("重试同步") } },
            dismissButton = { TextButton(onClick = { detailsOpen = false }) { Text("关闭") } }
        )
    }
}


private fun SyncStatus.historyLabel() = when (this) {
    SyncStatus.offline -> "离线"
    SyncStatus.syncing -> "进行中"
    SyncStatus.synced -> "已同步"
    SyncStatus.pending -> "待同步"
    SyncStatus.conflict -> "有冲突"
    SyncStatus.error -> "失败"
}
private fun MediaSyncStatus.historyLabel() = when (this) {
    MediaSyncStatus.idle -> "未检查"
    MediaSyncStatus.checking -> "检查中"
    MediaSyncStatus.ready -> "已检查"
    MediaSyncStatus.error -> "失败"
}

private fun HealthDetailStatus.historyLabel() = when (this) {
    HealthDetailStatus.neverRequested -> "未下载"
    HealthDetailStatus.loading -> "读取中"
    HealthDetailStatus.partial -> "部分缓存"
    HealthDetailStatus.complete -> "完整"
    HealthDetailStatus.error -> "失败"
}
private fun HealthDataset.historyLabel() = when (this) {
    HealthDataset.steps -> "步数"
    HealthDataset.calories -> "热量"
    HealthDataset.heartRate -> "心率"
    HealthDataset.restingHeartRate -> "静息心率"
    HealthDataset.sleep -> "睡眠"
    HealthDataset.body -> "身体测量"
    HealthDataset.activity -> "活动"
}
private fun String.historyLabel() = when (this) {
    "idle" -> "未请求"
    "requested" -> "已请求"
    "submitted" -> "已提交"
    "running" -> "采集中"
    "complete", "completed", "success", "succeeded" -> "完成"
    "published" -> "已有资料已发布"
    "failed" -> "失败"
    "mi-collection" -> "小米采集"
    "seed-read" -> "读取本机健康数据"
    "health-submit" -> "提交健康数据"
    "existing-data-publish" -> "发布已有健康数据"
    else -> this
}
