---
name: training-assistant
description: 通过个人训练应用的同一套开放接口安排分化训练、按实际器械查找动作与拉伸、读取真实完成记录并分析恢复；支持原始 kg/lb、修订冲突和完整备份。
---

# 个人训练应用操作

只操作用户指定的个人训练后端，不访问或改写 `misports.db`、`record/`、Hevy 或其他应用数据库。此技能是项目级技能，不安装到全局。

## 连接与工具

- 环境变量 `TRAINING_URL`：实际后端地址。
- 凭据仅使用 `TRAINING_TOKEN` 或 `TRAINING_TOKEN_FILE`。后者指向服务器生成的专用 token 文件；不要把凭据写入运动记录、脚本、项目文档或临时文件，不要输出凭据。
- 命令入口：本技能目录的 `scripts/training.py`，只依赖 Python 3 标准库。
- 先阅读项目内 `training-app/docs/API.md`。CLI 与 Android 都操作 `/v1`，没有助手专用数据库入口。
- 命令返回非零即未全部完成。HTTP 409 或退出码 3 表示冲突，必须比较版本后由用户明确选择；不要自动读取最新修订号重试覆盖。

```text
python scripts/training.py list gym
python scripts/training.py list equipment
python scripts/training.py list program
python scripts/training.py list plan
python scripts/training.py list muscle
python scripts/training.py list activity
python scripts/training.py get activity <活动ID>
python scripts/training.py get plan <计划ID>
python scripts/training.py next <分化方案ID>
python scripts/training.py recommend --type strength --muscles <部位ID列表> --gym <健身房ID>
python scripts/training.py recommend --type stretch --exercises <训练动作ID列表> --gym <健身房ID>
python scripts/training.py history --from YYYY-MM-DD --to YYYY-MM-DD
python scripts/training.py put plan <计划ID> --file <资料JSON> --base-revision <读取时的revision>
python scripts/training.py sync --file <SyncRequest.json>
python scripts/training.py export <备份ZIP路径>
python scripts/training.py import <备份ZIP路径>
python scripts/training.py put activity <活动ID> --file <资料JSON> --base-revision 0
```

上述尖括号是命令参数说明，执行时必须替换为实际查询得到的值。新资料使用独立 UUID 和 `baseRevision = 0`。编辑已有资料保留 `get` 取得的完整 Document 及其 `revision`，只修改 `data`；完整 Document 文件可以省略 `--base-revision`，工具会使用文件中原有的修订号。
当前方案、循环计划选择与手动下次选择是业务文档 `arrangement/current`，不是设备偏好。其 `data` 字段为 `{"id":"current","programId":"方案ID或null","selectedPlans":{"方案ID":{"slotId":"planId"}},"nextOverrides":{"方案ID":{"id":"稳定选择ID","slotId":"slotId"}}}`。用 `get arrangement current` 读取当前 revision，修改时只更新 `data` 并按该 revision 提交；新安排的 `baseRevision` 为 0。下次手动选择完成后由实际完成的 Session 消费；取消不消费，不应因取消手动清除选择。


## 安排训练

1. 读取健身房、器械、分化方案、已有计划和最近实际训练；不猜测健身房已有器械。
2. 查询 `next` 得到按完成情况计算的下一项。日期是可选安排，休息与取消不推进顺序；没有用户给定日期时只安排顺序，不假定连续每天训练。
3. 用部位和实际健身房查询多个动作候选。说明器械条件；无候选时说明缺少什么条件，不编造器械或动作。
4. 重量优先采用用户明确调整值，其次采用相同实际器械的最近真实记录。保留每组 `kg` / `lb`、总重量 / 单只重量 / 单侧配重、负重 / 助力；不进行显示单位归一化，不把未知重量写为零，不将助力等同于负重。
5. 力量动作通常预填 4 组，具体次数与负重按用户记录及当前要求配置；拉伸保留时长、次数、侧别及其自己的组要求。
6. 根据所选训练动作查询拉伸候选。只提供建议，确认用户选择及加入位置后才把拉伸写进计划，不自动追加固定收尾阶段。
7. 写入 `program` 或 `plan`，使用读取时的修订号。写入后再读取实际返回内容，说明安排的部位、器械、日期或顺序及每组要求。
8. 已经开始的训练拥有独立内容。调整后续计划不能改写 `session.snapshot`、已有动作说明和已完成组的目标；不要把新的计划强制应用到正在训练的实际记录。

## 分析实际记录

- 查询 `history`，时间统一 UTC+8。历史训练按 `historicalSource.localDate` 筛选；实时训练按真实 `startedAt` 转换日期，未知具体时间不构造顺序。
- 历史展示读取已整理的 `splitSlotName`；空值显示“自由锻炼”，标准推/拉/腿名称简写，其他明确分化名称原样保留。分类整理在导入前完成，读取时不随当前方案重新归类。
- 只有 `results` 中存在的组才是实际完成。`snapshot` 可为空；`items[].sets` 都是目标，不能当作训练完成量。
- 完成组的 `completedAt` 可为空；`segments` 按发生顺序保存组内多段重量与次数，一组仍只计一次。`Load.meaning=unknown` 表示保留读数但含义未知，显示时仅显示数值和单位。
- 完成、取消与进行中分开说明。取消训练中的实际完成组仍是事实，但取消不推进分化。
- `strengthSets` 与 `stretchSets` 分开；`strengthSetsByMuscle` 是主要部位涉及的力量组，复合动作可涉及多个部位，不把部位之和当作总组数。
- 同动作、同实际器械和同原始重量含义之间才比较负重。不同器械读数不能直接横向比较；不把助力增加解释为外部负重增加。
- 不从计划日期、空白组、界面计时或后台心率推断已完成训练。没有主观恢复反馈时明确未知，不虚构疲劳、受伤或恢复状态。
- 手机仍有待同步或断网时，后端不是完整的最新记录；说明读取到的数据时间范围，不宣称已经取得尚未上传的数据。

## 资料与备份

- 器械与动作图片先 `upload`，再保存 `media` 文档并更新使用者引用。素材使用新的不可变文件名，避免覆盖仍被旧资料使用的文件。
- 完整导出 ZIP 包含版本化 `data.json` 和 `media/`；不包含 token、手机授权和后端连接配置。新导出为 formatVersion 4；读取兼容版本 1–4，版本 1、2 定向清理已废弃历史来源字段，版本 3、4 拒绝该字段。独立运动以 `activity` 文档保存并随完整备份迁移，不计入力量组数。

- 历史来源合并仅限用户明确授权的 `record/1012.md`、`1013.md`、`1014.md`、`1015.md`、`1019.md`、`1020.md`、`1021.md` 和 `1026.md`；将它们作为历史会话合并导入，使用 `replace=false`。历史来源只保留 `sourceId` 与 `localDate`；整理后的真实反馈写入 `notes`，不把来源全文或计划表复制进记录。
- 本轮另行指定的活动来源是 2026-10-22 羽毛球 7200 秒（备注保留“约”）及 2026-10-17 徒步 12040 米、13854 秒。它们是独立 `activity` 记录，不扩大上述历史会话来源授权；使用稳定 `sourceId` 和明确的读取修订号，遇到不同 revision 冲突时保留两份内容并由用户明确选择。本轮不创建导入生产脚本。
- 导入前结合当时训练安排、实际动作及训练部位确定分类，写入 `splitSlotName` 后再提交。能够明确归入推、拉、腿的记录保存对应分类；确实无法明确归类的混合训练才保存空值。原始文件没有分类字段不代表自由锻炼。提交前逐日核对分类与实际组数，提交后核对接口及手机日历。
- 生产导入须等应用与后端更新后，由主助手按指定数据单独执行。0.0.3 旧库存、手机缓存与同步文档在 Session 解码边界定向移除废弃的 `historicalSource.originalText`，不改写数据库，也不自动转换 `notes`。新文档写入仍拒绝该字段；导出投影为版本 4，仅清除旧库存中的该字段。
- 删除使用明确修订号；被计划、器械或历史素材引用的内容需要先解除关联。删除实际训练会改变统计及分化顺序，必须先取得用户确认。
