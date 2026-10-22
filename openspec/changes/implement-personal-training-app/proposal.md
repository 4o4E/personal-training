# Proposal

## Why

用户已完成实施前确认，要求开始开发个人训练应用。原始完整需求以 `../define-personal-training-app/` 的七项能力规范为准，本变更实施这些需求，并补充本次确认的离线同步、训练轮换和拉伸加入规则。

## What Changes

- 在独立的 `training-app/` 中实现原生 Kotlin + Jetpack Compose 安卓应用、Ktor + SQLite 自有后端及共享业务接口操作 skill。
- 手机使用 Room 保存完整训练数据，已下载资料可离线使用，网络恢复后同步；冲突需明确选择，不能静默覆盖。
- N 分化按实际完成顺序轮换，可指定日期；休息不会跳过训练部位，未指定日期只显示后续顺序。
- 根据实际训练匹配拉伸候选，由用户或助手决定是否加入及加入位置，不自动生成末尾拉伸段。
- 创建用户已授权的独立应用数据库，区分计划与实际训练，并保留开始训练时的计划内容、原始计量含义及独立动作素材。
- 实现完整的健身房及器械维护、中文动作教学、肌肉图解、计划与实际记录、原生休息提醒、导入导出、唯一 token 鉴权和开放接口。
- 使用 GitHub Actions 构建、检查并发布公开 GHCR 后端镜像，源码仓库保持私有，项目内提供匿名拉取 `latest` 的 Compose，用户手动部署。
- 浏览器只读边界继续有效；本次不新增浏览器客户端或 iOS，不修改既有训练资料或小米数据库。

## Capabilities

### New Capabilities

- `offline-training-sync`：完整离线训练、明确同步状态、版本冲突处理及独立数据迁移。
- `split-rotation`：按完成顺序推进 N 分化，日期为可选安排，不把休息视为完成训练。
- `stretch-selection`：依据实际训练和器械推荐拉伸，由用户或助手决定加入位置。

### Modified Capabilities

无已发布规范需要修改。此前需求记录变更中的 `android-training-runtime`、`training-programs`、`gym-equipment`、`exercise-stretch-library`、`training-data-access`、`read-only-web`、`project-workflow` 仍为完整实现约束，不因本变更只新增三项确认规则而减少其范围。

## Impact

- 新增独立 Gradle 工程的安卓、共享业务定义和服务端模块，以及素材、部署文件、接口文档和操作 skill。
- 服务端与手机只创建应用自己的数据结构。数据包含部位、动作、教学素材、健身房与器械、分化与训练顺序、日期计划及计划组、实际训练及实际组；同步版本和计时状态单独保存。
- 后端选择由用户确认为 Ktor + SQLite，客户端离线存储使用 Room。所需结构建立已获本次确认授权，现有 `record/`、`misports/` 保持原样。
