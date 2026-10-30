---
name: training-assistant
description: 通过个人训练应用的同一受鉴权接口安排分化训练、读取真实训练与健康记录、请求服务器采集并处理修订冲突和完整备份。
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
2. 查询 `next` 得到按真实组推进的下一项。每天至多一份训练记录；有至少一组实际结果即视为练过，取消保留实际组也推进，零组不推进。没有用户给定日期时只安排顺序，不假定连续每天训练。
3. 用部位和实际健身房查询多个动作候选。说明器械条件；无候选时说明缺少什么条件，不编造器械或动作。动作目录中的教学步骤为空表示没有已记录的具体形式；保留名称、部位、计量等真实字段，不用同名器械或经验补写教学。
4. 重量优先采用用户明确调整值，其次采用相同实际器械的最近真实记录。保留每组 `kg` / `lb`、总重量 / 单只重量 / 单侧配重、负重 / 助力；不进行显示单位归一化，不把未知重量写为零，不将助力等同于负重。
5. 力量动作通常预填 4 组，具体次数与负重按用户记录及当前要求配置；拉伸保留时长、次数、侧别及其自己的组要求。
6. 根据所选训练动作查询拉伸候选。只提供建议，确认用户选择及加入位置后才把拉伸写进计划，不自动追加固定收尾阶段。
   需要浏览 SmartWorkout 来源动作时，使用 `GET /v1/smartworkout/exercises` 搜索名称并查看来源器械分类，用 `GET /v1/smartworkout/exercises/{sourceId}/guide` 核对步骤和媒体。用户确定选择后，向 `POST /v1/smartworkout/exercises/{sourceId}/select` 提交当前训练或拉伸类型，使用返回的动作 ID 安排计划；不得仅按名称将不同器械或动作变体当作同一动作。来源器械未知不等于健身房具备器械，视频未提供不阻止选择。接口及鉴权规则见 `training-app/docs/API.md`。
7. 写入 `program` 或 `plan`，使用读取时的修订号。写入后再读取实际返回内容，说明安排的部位、器械、日期或顺序及每组要求。
8. 已经开始的训练拥有独立内容。调整后续计划不能改写 `session.snapshot`、已有动作说明和已完成组的目标；不要把新的计划强制应用到正在训练的实际记录。

## 分析实际记录

- 查询 `history`，时间统一 UTC+8。所有 Session 以必填 `localDate` 筛选；实时来源的精确时间保留在 `sourceRecords.startedAt`，未知具体时间不构造顺序。
- 每日唯一 `Session` 使用 `day-YYYY-MM-DD`。`sourceRecords` 仅保留来源事实（原Session id、名称、日期、原计划快照、已知时间、分类、动作映射和来源原反馈），不复制实际组或Markdown正文；真实组、分段和当前可编辑反馈只在canonical Session根字段。canonical读取/备份/重启保持原payload，不按旧来源反馈重建根反馈。
- `sessionTrainingLabels` 从实际组关联的来源分类汇总；同日存在多个真实分类时全部保留。零组准备内容按自身计划分类；空分类显示“自由锻炼”，标准推/拉/腿名称简写，其他明确名称原样保留。
- 只有 `results` 中存在的组才是实际完成。`snapshot` 可为空；`items[].sets` 都是目标，不能当作训练完成量。只有实际组关联的根或来源 `snapshot` 才算已执行计划。
- 完成组的 `completedAt` 可为空；`segments` 按发生顺序保存组内多段重量与次数，一组仍只计一次。`Load.meaning=unknown` 表示保留读数但含义未知，显示时仅显示数值和单位。
- 每次训练保存一组后，除整次训练所有有效目标中的最后一组外都会按该组休息时长计时；最后一组不休息，不是每个动作的最后一组。自动休息只由真实提交的实际组触发。
- 同动作、同实际器械和同原始重量含义之间才比较负重。不同器械读数不能直接横向比较；不把助力增加解释为外部负重增加。
- 不从计划日期、空白组、界面计时或后台心率推断已完成训练。没有主观恢复反馈时明确未知，不虚构疲劳、受伤或恢复状态。
- 手机仍有待同步或断网时，后端不是完整的最新记录；说明读取到的数据时间范围，不宣称已经取得尚未上传的数据。
## 导航与训练操作补充

- 左侧栏打开时系统返回优先关闭侧栏，不执行页面返回或退出；关闭侧栏后才按当前目的地处理返回，训练草稿、计时及位置保持。
- 次数和重量的拖动输入必须在系统长按识别成功后才激活；成功时新增一次系统轻触反馈，之后真实跨刻度调整继续原有轻触反馈。长按成功前的滑动应交由页面滚动处理，不调整数值或触发触觉。
- 休息提醒声音默认关闭；用户选择铃声后才循环播放，并使用系统媒体音量。不要将声音描述或判断为使用闹钟音量。静音时仍依系统授权触发震动。

## 健康数据与服务器采集

健康操作与 Android 共用同一个受鉴权 `/v1` API 和 token；不要访问源SQLite或建立助手专用接口。先读取 `GET /v1/capabilities`，只有 `formatVersion=6` 且 `healthApiVersion=1` 才使用本轮健康契约；能力不符时停止写入并按真实状态提示升级端点。


从本目录使用现有 `scripts/training.py`，凭据继续由 `TRAINING_URL` 与 `TRAINING_TOKEN` / `TRAINING_TOKEN_FILE` 提供。每条命令只返回对应接口的JSON；`health-records` 每次仅读取一页，不自动遍历后续游标。

```sh
python scripts/training.py capabilities
python scripts/training.py health-status
python scripts/training.py health-days --from 2026-10-01 --to 2026-10-31
python scripts/training.py health-records --date 2026-10-27 --dataset heartRate
python scripts/training.py health-records --date 2026-10-27 --dataset heartRate --cursor "<nextCursor>"
```

仅在用户明确要求时执行`python scripts/training.py health-collect`；它对`/v1/health/collection/request`发送无请求体的POST，不会自动下载所有健康明细。
健康运动摘要应仅按只读健康记录分析；不得把采集摘要或自动投影当作用户手动运动，也不得因此创建或更新 `activity` 文档。用户运动日历只依据明确手动录入或明确导入的活动。历史自动投影仅当活动ID以`xiaomi-`开头且`sourceId`以`misports:activities:`开头时才从用户运动视图排除；不要仅按sourceId前缀过滤，明确导入的`activity-misports-20261017-walking`及手动UUID须保留。健康来源记录仍保留且只读，不删除、不改写。
- 按日期范围读取 `GET /v1/health/days?from=YYYY-MM-DD&to=YYYY-MM-DD`，两端日期包含在内；查看真实明细使用 `GET /v1/health/records?date=YYYY-MM-DD&dataset=steps&cursor=...`。数据集仅为 `steps`、`calories`、`heartRate`、`restingHeartRate`、`sleep`、`body`、`activity`。详情页固定最多500条，按返回的 `nextCursor` 续读；只有同一日期修订下所有分页完成才算完整，未取到数据不等于没有记录。
- 分析运动期间心率时，以 `source=xiaomi_workout`、`sampleType=active` 的真实运动采样及其时间为准，按运动起止筛选；日常稀疏采样不能代表完整运动曲线。心率明细可能包含数千条，须读取全部分页后再判断覆盖范围。
- 只有使用实际归一化来源批次时才调用`POST /v1/health/import`，请求体为`{dataset, records}`；每条记录为`{sourceId, localDate, measuredAt, payload}`，响应包含实际`accepted`、`unchanged`与`revisionByDate`。同一数据重试必须保持自然来源键不变以实现幂等。健康活动摘要是只读健康事实；通过健康接口导入摘要不得自动创建或更新`activity`文档。保留源字段、缺失值与原单位，时间戳为epoch毫秒、日期为UTC+8；睡眠`asleepMinutes`、`awakeMinutes`，活动`durationSeconds`、`mainPosture`均可能为空，`mainPosture`保留源整数编码；不能构造样例健康记录或填充缺失测量。
- 通过 `GET /v1/export` 获取后端完整资料；清单位于 `data.json.healthManifest`，其 `scope`、`coveredDates`、`datasets`、`recordCount` 描述范围；健康明细逐行位于 `health.jsonl`，每行 `{dataset,record}`，其中record为 `{sourceId,localDate,measuredAt,payload}`。只有 `scope=complete` 才是完整健康备份。完整资料在线通过 `POST /v1/import?replace=false` 恢复；不同内容返回冲突且不修改。仅用户明确选择覆盖已有差异时才用 `replace=true`。手机本机ZIP的 `scope=cache` 只包含已下载缓存范围，不代表后端完整备份。

服务器采集只在已配置持久状态、种子副本和token文件后运行；定时器与服务器重试使用同一串行任务，不在个人电脑另开自动采集。


## 资料与备份

- 器械与动作图片先 `upload`，再保存 `media` 文档并更新使用者引用。素材使用新的不可变文件名，避免覆盖仍被旧资料使用的文件。
- 完整训练导出含版本化 `data.json` 和 `media/`，不包含 token、手机授权和后端连接配置。当前格式为 v6，读取兼容 v1–v5；v1–v4 历史来源归并规则不变，v5 canonical 每日训练保持原内容和明确恢复/冲突约束。完整后端 ZIP 的健康清单位于 `data.json.healthManifest`，逐条记录另存为 `health.jsonl`；只有 `scope=complete` 才可称完整健康备份。手机导出范围必须按清单说明，不能当作完整后端备份。完整健康资料须在线经既有 `/v1/export`、`/v1/import?replace=false|true` 迁移，替换前必须取得明确授权。

- 历史来源合并仅限用户明确授权的 `record/1012.md`、`1013.md`、`1014.md`、`1015.md`、`1019.md`、`1020.md`、`1021.md` 和 `1026.md`；将它们作为历史会话合并导入，使用 `replace=false`。`sourceRecords` 只保留必要原事实及来源原反馈，不复制完成组或来源正文；重复旧来源导入不覆盖canonical根级用户反馈。v5 canonical每日训练仍按原规则要求用户明确选择整日恢复。
- 本轮另行指定的活动来源是2026-10-22羽毛球7200秒（备注保留“约”）及2026-10-17徒步12040米、13854秒。它们是明确导入的独立`activity`记录，使用稳定`sourceId`和明确的读取修订号，遇到不同revision冲突时保留两份内容并由用户明确选择；不能把后续小米自动摘要合并为活动更新或误滤徒步。此授权不扩大上述历史会话来源范围，本轮不创建导入生产脚本。
- 导入前结合当时训练安排、实际动作及训练部位确定分类，写入 `splitSlotName` 后再提交。能够明确归入推、拉、腿的记录保存对应分类；确实无法明确归类的混合训练才保存空值。原始文件没有分类字段不代表自由锻炼。提交前逐日核对分类与实际组数，提交后核对接口及手机日历。
- 生产训练迁移须等应用与后端更新后，由主助手按指定数据单独执行；v1、v2读取时清理废弃的`historicalSource.originalText`，v3、v4拒绝该字段，v5强制canonical每日格式。健康缓存与记录位于独立缓存，不进入普通训练documents；按Room非破坏性迁移保留既有离线资料。
- 删除使用明确修订号；被计划、器械或历史素材引用的内容需要先解除关联。删除实际训练会改变统计及分化顺序，必须先取得用户确认。
