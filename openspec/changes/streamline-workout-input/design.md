# Design

## Context

动机与范围见proposal.md。ActualSetRow通过rememberSaveable保持实际读数，完成组复用Repository写入与计时。AppPreferences管理本机外观等交互选择，Equipment没有真实重量档位数据。Compose BOM为2025.12.01，UI为1.10.0，支持官方拖动手势与系统刻度触觉；不需要新增网络服务或控件库。

## Goals / Non-Goals

**Goals:** 通过紧凑数值控件统一拖动、按钮与直接输入；训练页面明确当前动作并保留未提交草稿；复用现有实际组写入、记录修正与计时。

**Non-Goals:** 不修改计划编辑、日历记录弹窗、后端协议、数据库结构或资料格式；不推断器械档位，不换算kg/lb，不重做计时服务。

## Decisions

### 已确认决定与来源

- 用户要求重量与次数均有按钮调整，在跨刻度时震动，重量采用效果一致的方式且保留手输。
- 用户要求动作名称单次展示、删除力量训练标签、保持原有连续进度条样式、计划位于动作标题下且与记录分开，完成与当前动作分别高亮。
- 用户确认数值框旁使用小型拖动按钮，按住上下拖动调整，不占用整块刻度尺区域；次数每档1次，重量可选0.5、1、2.5、5、10步长并记住动作选择；拖动必须先通过系统长按识别才激活，长按成功时轻触反馈一次，之前滑动由父级页面正常滚动处理；当前动作蓝色展开、完成动作绿色、其他动作紧凑；完成整项后突出下一个未完成动作。
- 用户要求设计后由Luna实施、主助手验收，每次完成交付正式签名生产APK。

### 数值控件使用官方拖动与触觉能力

采用Foundation拖动手势处理数值框旁的小型按钮，并采用系统长按识别成功作为拖动激活门槛。长按成功前不拦截父级滚动、不调整数值、不触发触觉；成功后才显示拖动状态并处理手势，向上增加、向下减少，累计约12dp位移调整一档，松手停止且不惯性滚动。两行使用同一网格：48dp减号、80×40dp数值框、48dp加号、48dp拖动区域、48dp菜单区域，间距4dp。窄容器按剩余宽度缩小数值框。输入使用BasicTextField与Material颜色，取消浮动标签，单位放在框内；次数保留菜单等宽位置。重量步长和常用重量通过独立菜单选择，不额外占行或改变两行按钮位置。使用现有Material组件，不引入独立控件库。

直接输入保留有效小数而非强行取整；改变步长不得直接修改当前数值。刻度值通过十进制步进计算，避免0.5、2.5反复调整引入浮点显示误差。空白保持未填写，不能因绘制控件就写成零；原业务校验继续在提交处执行。手势生命周期不以当前数值作为key，连续拖动使用最新草稿累计，避免首次调整后手势被重建。

拖动区域按下时显示局部临时竖向刻度，在手指旁展示当前数值与附近真实步长，松手或取消立即收起。刻度只作为当前草稿的显示，不写入记录、不增加页面静态高度、不抢手势或键盘焦点。复用Compose Popup/绘制能力，只显示有限相邻刻度；按下、移动和结束共用同一手势生命周期。参考Dial Slider的原生Android示例与CameraZoomDial已验证源码中的紧凑控件展开模式，不引入其相机专用计算或新依赖。

触觉使用LocalHapticFeedback：系统长按识别成功时新增一次系统轻触反馈，之后每个用户真实跨越的调整刻度继续沿用既有轻触反馈；加减成功也可提供对应反馈。Compose UI 1.10.0通过ViewCompat进行Android兼容映射，一加9R的旧系统可使用系统提供的轻反馈。初始化、恢复、手输、长按未成功与页面重组均不反馈，不自行构造强震波形。

### 并行实现接口

数值控件任务拥有WorkoutValueInput.kt、AppPreferences.kt及相关行为测试。对界面任务提供：

- `@Composable internal fun WorkoutCountInput(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier)`。
- `@Composable internal fun WorkoutWeightInput(value: String, onValueChange: (String) -> Unit, unit: String, step: Double, onStepChange: (Double) -> Unit, modifier: Modifier = Modifier, commonWeights: List<Double> = emptyList())`。
- `DeviceChoices.weightSteps: Map<String, Double> = emptyMap()`；`internal fun weightStepKey(exerciseId: String, equipmentId: String?, unit: String): String`；`AppPreferences.weightStep(key: String, value: Double)`。界面读取偏好中的key，未设置时沿用现有默认步长1。

界面任务拥有WorkoutScreen.kt、CompletedTrainingScreen.kt及Editors.kt的实际记录分支，调用上述接口，不修改数值模块和偏好文件。计划编辑保持原输入方式；不触碰DayHistoryEditor。已完成记录中的复杂分段输入继续保留，每段仍能手动修正。

步长属于本机交互偏好，按动作、器械与单位组合记忆；不新增器械档位字段，不同步为训练业务数据，不修改Room或后端表。

### 当前动作和草稿的状态组织

以实际item标识保存用户选中的未完成动作；初次取第一个有效未完成动作。只因当前动作全部目标完成才选择下一有效未完成动作，不因任意重组或其他动作记录变化抢走用户选择。撤销完成、替换剩余动作及追加目标继续遵循真实结果和现有items/skippedItems，不把被替换的目标算成完成。

展开区域使用按session/item/set标识的SaveableStateHolder或等效稳定草稿状态，切换动作后ActualSetRow的输入状态仍能恢复；不能让新增的折叠逻辑销毁rememberSaveable草稿。下一组仍继承最近实际组，当前草稿优先，原计划保持。

动作卡片显示标题和教学图标、计划摘要、原有样式的连续进度条及真实完成组数，再显示当前输入与独立实际记录区域。只有当前动作默认展开输入，已完成结果默认收起且可展开修改或撤销。不同组计划不得被合并成错误的统一目标；与动作标题相同的备注不重复显示。休息显示紧凑计时区域，继续使用原截止时间、停止提醒与结束休息操作，不重复动作名。当天已结束页面复用相同状态和计划/记录表达，继续训练与实际结果修正入口保留。

### 验收与部署

主助手在一加9R独立验证应用中，使用已有真实训练资料的隔离副本完成拖动、按钮、手输、切换草稿、保存继承、进度与休息场景；不修改正式应用或生产数据。通过真实设备振动服务记录核对触觉请求与初始化静默；主观舒适度不以远程截图冒充验证。

实现结束集中编译与必要行为回归，实机检查浅深色，恢复原验证资料，再验证正式APK签名和安装属性。生产APK可手动覆盖安装，无需更新后端；回退使用同签名既有APK，现有训练数据格式不变。

## Risks / Trade-offs

- [连续拖动与外部值更新互相覆盖] → 单一草稿值、稳定手势生命周期，显示值与提交值一致；实测手输、按钮和拖动交替。
- [折叠动作导致草稿丢失] → 按实际session/item/set保存界面状态，验收主动切换及离开返回。
- [步进将小数重量取整] → 使用十进制运算，改变步长不改现值，手输保持原读数与单位。
- [触觉强度随设备变化] → 使用系统语义反馈及用户系统设置；在一加9R核对请求与频率，不宣称远程验证了主观手感。

## References

- 官方拖动手势：https://developer.android.com/develop/ui/compose/touch-input/pointer-input/drag-swipe-fling
- 官方触觉类型：https://developer.android.com/reference/kotlin/androidx/compose/ui/hapticfeedback/HapticFeedbackType
- Compose 1.10.0触觉兼容实现：https://android.googlesource.com/platform/frameworks/support/+/a1a52f4350c7cb9288486c6fbc7c03af2d6ef9b9/compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/platform/HapticFeedback.android.kt
- 开源原生Android示例及按住展开说明：https://github.com/ngocdevv/dial-slider/blob/master/kotlin/README.md
- CameraZoomDial手势源码：https://github.com/ngocdevv/dial-slider/blob/master/kotlin/dial-slider/src/main/kotlin/com/ngocdevv/dialslider/camerazoom/CameraZoomDial.kt
