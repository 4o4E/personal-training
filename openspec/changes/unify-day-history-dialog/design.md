# Design

## Context

动机见proposal.md。现有HistoryScreens.kt使用逐组IconButton和InfoCard展示日期记录，反馈仅为文字。HealthHistoryScreen.kt已有七类健康明细、分页缓存状态和真实量值格式化；其中HealthSyncCard是运动记录顶部唯一同步入口，必须保留。TrainingRepository已有Document修订冲突语义和Room事务，历史完成读数与反馈可持久化，但没有日期级批量保存入口。不存在需要改变的后端数据模型。

## Goals / Non-Goals

**Goals:** 把日期弹窗作为同日资料的唯一浏览和编辑入口，在现有Document与健康缓存模型上提供独立草稿和一次本地提交。

**Non-Goals:** 不更改健康原始资料、采集投影、后端协议或数据库结构，不改变进行中会话和计时，不新增运动采样或图表。

## Decisions

### 已确认决定

用户原文：“健康数据应该和运动数据放在一起，同样放在运动记录的弹窗里……修改应该只有一个按钮，点击后整个弹窗内可以修改的内容都变成输入框……可以取消或者保存”。在明确原始健康指标只读、运动和反馈可编辑、删除暂存以及统一保存范围后，用户选择“按此范围开始开发”。

### 实施选择：复用健康读取与紧凑展示

将健康浏览内容改为日期弹窗内的展示模块，保留HealthSyncCard。复用refreshHealthDays/loadHealthDate和已有分页、格式化能力；日历范围包含健康最早可读日期，查看历史月份按需加载汇总，避免每次加载全部采样。以sourceId将小米运动明细附在对应ActivityRecord，不重复运动卡片。没有关联的设备运动仍可在健康明细查看；原始指标只读。日期弹窗内使用紧凑文字行和必要的组间分隔，不全局改动InfoCard。

### 实施选择：界面独立草稿

进入修改时快照当天可编辑的Document，编辑控件保存文本草稿；健康下载和数据流更新不重建草稿。保存时统一解析全部输入，构造保留未编辑字段的Document集合；标记删除使用deleted=true，允许撤销。取消只丢弃草稿，保存成功才退出编辑。字段解析错误留在弹窗中。外侧或返回对有修改的草稿请求放弃确认，阅读模式无关闭按钮。

### 实施选择：一个日期提交接口

新增TrainingRepository.saveDayHistory(originals: List<Document>, changes: List<Document>)，由TrainingViewModel.saveDayHistory(originals, changes, onSaved, onError)调用。onSaved仅在持久化成功后调用；onError接收可显示的输入或保存错误，界面保留草稿。originals为进入修改时的原文档，changes仅包含改变或删除的既有session/activity文档。界面不调用多次save/updateCompletedResult/updateCompletedNotes完成同一提交。

保存入口复用save的acknowledgedOnly、原revision和remote冲突处理；所有文档验证完成后，在同一Room事务写入，随后一次install和enqueueSync。无改变不写入。只允许历史实际结果、反馈和其他运动编辑；保留ID、来源、训练日期、状态、计划快照、完成时间、组身份和分段数量。分段总次数一致、按次数或秒数的动作语义沿用已有校验。删除不改变计时。现有正在进行或当天完成训练页面的专用编辑方法保留，仅删除失去调用方的历史专用入口。

### 实施与验收分工

GPT-6-Luna数据任务拥有TrainingRepository.kt、TrainingViewModel.kt以及必要的保存校验与回归测试；GPT-6-Luna界面任务拥有HistoryScreens.kt、HealthHistoryScreen.kt、必要的新编辑组件和界面调用方。双方遵守上面的Document接口，不创建子代理、不在开发中途构建或运行测试。主助手完成规范、接口协调、最终代码验收和一次集成检查，再在一加9R调试应用验证。

助手建议已包含在用户本次确认范围中，无待确认业务或架构选择。

## Risks / Trade-offs

- 健康刷新使草稿内容重置 → 草稿只在进入编辑时创建，退出编辑后才采用新阅读状态。
- 多条记录部分保存 → 先解析并验证全部修改，单个本地事务写入；云端继续使用现有逐文档修订协议，不声称云端跨文档原子提交。
- 来源运动重复或手工时间被覆盖 → 按稳定sourceId展示关联，用户字段与只读设备原始字段分别展示，保存原sourceId。
- 日期弹窗内容过长 → 按动作紧凑展示，健康明细按需展开与读取，单一滚动区域，不压缩输入控件必要的触摸面积。
- 调试数据进入生产 → 仅使用独立验收后端与9R调试包，保留并恢复调试资料，不操作正式应用或生产数据。

## Migration Plan

无需数据迁移或后端升级。覆盖安装Android应用后，既有训练、活动及健康缓存继续使用。验收使用真实历史副本，核对保存前后的非编辑字段、离线重启、恢复同步及源健康只读状态；保留正式发布及生产操作由用户另行决定。
