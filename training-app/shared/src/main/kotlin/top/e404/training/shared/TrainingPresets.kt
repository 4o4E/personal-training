package top.e404.training.shared

/** 候选描述器械条件而非效果等级；动作顺序人工编排，较少动作保留各训练项的主要内容。 */
enum class PresetStyle(val title: String, val description: String) {
    MACHINES("固定器械与绳索为主", "使用推胸、划船、腿部等器械及绳索；具体机型要求列在各动作中。"),
    DUMBBELLS("哑铃与训练凳为主", "主要使用哑铃及训练凳，部分动作不需要专用器械；不要求登记健身房。"),
    MIXED("混合器械", "结合杠铃、哑铃、固定器械和绳索；所需器械类型较多，按实际条件选择。")
}
data class PresetSplit(val count: Int, val title: String, val description: String)
val presetActionCounts = 3..5
val presetSetCounts = 2..4
val presetSplits = listOf(
    PresetSplit(3, "三分化", "推类训练／拉类训练／腿部训练，一轮三次；胸肩三头及背部二头分别组合。"),
    PresetSplit(4, "四分化", "胸部与三头／背部与二头／腿部／肩部与腹部，一轮四次；肩部单独安排。"),
    PresetSplit(5, "五分化", "胸部／背部／腿部／肩部与腹部／手臂，一轮五次；手臂独立安排。")
)
private data class PresetSection(val name: String, val muscles: List<String>)
private val sections = mapOf(
    "push" to PresetSection("推类训练", listOf("chest", "shoulders", "triceps")),
    "pull" to PresetSection("拉类训练", listOf("lats", "upper_back", "biceps")),
    "legs" to PresetSection("腿部训练", listOf("quadriceps", "hamstrings", "glutes", "calves")),
    "chestArms" to PresetSection("胸部与三头", listOf("chest", "triceps")),
    "shoulders" to PresetSection("肩部与腹部", listOf("shoulders", "abdominals")),
    "chest" to PresetSection("胸部训练", listOf("chest")),
    "back" to PresetSection("背部训练", listOf("lats", "upper_back")),
    "arms" to PresetSection("手臂训练", listOf("biceps", "triceps", "forearms"))
)
private val splitSections = mapOf(3 to listOf("push", "pull", "legs"), 4 to listOf("chestArms", "pull", "legs", "shoulders"), 5 to listOf("chest", "back", "legs", "shoulders", "arms"))
private val recipes = mapOf(
    PresetStyle.MACHINES to mapOf(
        "push" to listOf("exercise_fbf92739", "exercise_9237bad1", "exercise_94b7239b", "exercise_78683336", "exercise_d5d0354d"),
        "pull" to listOf("exercise_473cf5b8", "exercise_1df4a847", "exercise_af328e3d", "exercise_d8281c62", "exercise_9273ba17"),
        "legs" to listOf("exercise_c7973e0e", "exercise_b8127ad1", "exercise_e05c2c38", "exercise_75a4f6c4", "exercise_68ce0b9b"),
        "chestArms" to listOf("exercise_fbf92739", "exercise_94b7239b", "exercise_78683336", "exercise_21310f5f", "exercise_651f844c"),
        "shoulders" to listOf("exercise_9237bad1", "exercise_d5d0354d", "exercise_dcf3b31b", "exercise_d8281c62", "exercise_23a48484"),
        "chest" to listOf("exercise_fbf92739", "exercise_78683336", "exercise_99c1f2ad", "exercise_24706dcd", "exercise_651f844c"),
        "back" to listOf("exercise_473cf5b8", "exercise_1df4a847", "exercise_d8281c62", "exercise_9273ba17", "exercise_f1d60854"),
        "arms" to listOf("exercise_af328e3d", "exercise_94b7239b", "exercise_36e8f14e", "exercise_3092fadd", "exercise_9f48f858")
    ),
    PresetStyle.DUMBBELLS to mapOf(
        "push" to listOf("exercise_3601968b", "exercise_9930df71", "exercise_3765684d", "exercise_422b08f1", "exercise_12017185"),
        "pull" to listOf("exercise_f1e57334", "exercise_914f3a96", "exercise_37fcc2bb", "exercise_e5988a0a", "exercise_7e3bc8b6"),
        "legs" to listOf("exercise_3d0c7c75", "exercise_72cffad5", "exercise_6da40660", "exercise_20c1a3cb", "exercise_da5430fc"),
        "chestArms" to listOf("exercise_3601968b", "exercise_3765684d", "exercise_12017185", "exercise_07b38369", "exercise_6127a3ad"),
        "shoulders" to listOf("exercise_9930df71", "exercise_422b08f1", "exercise_dcf3b31b", "exercise_e5988a0a", "exercise_09c9f635"),
        "chest" to listOf("exercise_3601968b", "exercise_12017185", "exercise_07b38369", "exercise_f72fa239", "exercise_756ee329"),
        "back" to listOf("exercise_f1e57334", "exercise_914f3a96", "exercise_e5988a0a", "exercise_23e92538", "exercise_67280085"),
        "arms" to listOf("exercise_37fcc2bb", "exercise_3765684d", "exercise_7e3bc8b6", "exercise_68f8a292", "exercise_d8460fa6")
    ),
    PresetStyle.MIXED to mapOf(
        "push" to listOf("exercise_79d0bb3a", "exercise_9930df71", "exercise_94b7239b", "exercise_422b08f1", "exercise_07b38369"),
        "pull" to listOf("exercise_6a6c31a5", "exercise_1df4a847", "exercise_37fcc2bb", "exercise_d8281c62", "exercise_9273ba17"),
        "legs" to listOf("exercise_d04ac939", "exercise_2b4b7310", "exercise_e05c2c38", "exercise_75a4f6c4", "exercise_da5430fc"),
        "chestArms" to listOf("exercise_79d0bb3a", "exercise_94b7239b", "exercise_78683336", "exercise_07b38369", "exercise_3765684d"),
        "shoulders" to listOf("exercise_9930df71", "exercise_be289e45", "exercise_dcf3b31b", "exercise_d8281c62", "exercise_23a48484"),
        "chest" to listOf("exercise_79d0bb3a", "exercise_07b38369", "exercise_78683336", "exercise_756ee329", "exercise_651f844c"),
        "back" to listOf("exercise_6a6c31a5", "exercise_1df4a847", "exercise_d8281c62", "exercise_f1e57334", "exercise_55e6546f"),
        "arms" to listOf("exercise_a5ac6449", "exercise_94b7239b", "exercise_7e3bc8b6", "exercise_3765684d", "exercise_d8460fa6")
    )
)
data class PresetDay(val name: String, val muscles: List<String>, val exercises: List<Exercise>, val unavailable: List<String>)
data class PresetPreview(val split: PresetSplit, val style: PresetStyle, val days: List<PresetDay>, val unavailable: List<String>)

fun previewPreset(count: Int, style: PresetStyle, actions: Int, exercises: Map<String, Exercise>, gymId: String?, equipment: List<Equipment>): PresetPreview {
    require(actions in presetActionCounts) { "请选择三个至五个动作" }
    val split = presetSplits.single { it.count == count }
    val days = splitSections.getValue(count).map { key ->
        val issues = linkedSetOf<String>()
        val section = sections.getValue(key)
        val selected = recipes.getValue(style).getValue(key).take(actions).mapNotNull { id ->
            val exercise = exercises[id]
            if (exercise == null) issues += "${section.name}的动作资料不完整"
            else if (!exerciseAvailable(exercise, gymId, equipment)) issues += "${section.name}：${exercise.name}缺少所需器械"
            exercise
        }
        PresetDay(section.name, section.muscles, selected, issues.toList())
    }
    return PresetPreview(split, style, days, days.flatMap { it.unavailable })
}

data class RotationPlan(val slot: SplitSlot, val plan: Plan?)

/** 日期安排只执行一次；无日期的分化计划循环使用。多份模板必须明确选择，不能猜测用户偏好。 */
fun rotationPlans(
    program: Program, plans: List<Plan>, sessions: List<Session>, selectedPlans: Map<String, String>,
    override: ArrangementOverride? = null
): List<RotationPlan> {
    val completed = sessions.asSequence().filter { it.status == "completed" }.mapNotNull { it.snapshot?.id }.toHashSet()
    val next = nextSlot(program, sessions, override)
    val start = program.slots.indexOfFirst { it.id == next.id }
    return List(program.slots.size) { offset ->
        val slot = program.slots[(start + offset) % program.slots.size]
        var dated: Plan? = null
        var template: Plan? = null
        var preferred: Plan? = null
        var templateCount = 0
        plans.forEach { plan ->
            if (plan.programId == program.id && plan.slotId == slot.id) {
                if (plan.date != null) {
                    if (plan.id !in completed && (dated == null || plan.date < dated!!.date!!)) dated = plan
                } else {
                    templateCount++
                    template = plan
                    if (plan.id == selectedPlans[slot.id]) preferred = plan
                }
            }
        }
        RotationPlan(slot, dated ?: if (templateCount == 1) template else preferred)
    }
}
