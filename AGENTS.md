# 个人训练应用开发

- 本目录是独立 Git 与 Gradle 项目。使用 Windows 原生工具，不使用 WSL 的 Bash。
- 需求、验收要求和已确认的架构决定使用官方 OpenSpec，入口为 `openspec/config.yaml` 和 `openspec/changes/`；修改前读取相关规范及变更材料。使用项目 `.agents/skills/openspec-*` 工作流程维护，不建立另一套决定记录。
- 工程技能正文位于 `.agents/skills/`，`.omp/commands/` 提供 OpenSpec 命令入口；来源和版本记录在 `skills-lock.json`。领域术语只维护在 `GLOSSARY.md`，开发过程记录在被忽略的 `.workspace/WORKLOG.md`。
- 用户确定设计后，由 GPT-6-Luna 完成开发，主助手负责审查和验收。未经用户确认的架构选择不得当作已确认决定。
- 不覆盖外层 `../record/` 的训练记录，不改写或提交 `data/xiaomi/` 的私人数据库、原始响应及凭据。小米采集器位于 Kotlin JVM Gradle 模块 `collector/`；正式手机及生产服务的修改需有用户授权。
- 每次完成应用修改并通过验收后，使用既有正式签名构建生产 Release APK，验证签名及正式包属性，并提供 APK 路径；不交付 Debug APK。构建不代表发布 GitHub Release、升级后端或安装手机应用。
