# Proposal

## Why

当前 CI 只发布后端镜像，应用安装包仍需本机构建，手机没有检查更新、下载和安装入口。需要以同一个版本标签发布应用和后端，使镜像直接复用已发布的后端运行包，减少重复构建并提供可直接使用的更新路径。

## What Changes

- 用户要求 CI 同时打包安卓应用和后端，在 GitHub Release 发布，再复用 Release 产物构建 GHCR 镜像，通过 Git 标签控制正式发布。
- 用户确认现有 `4o4E/personal-training` 仓库保持 Private、GHCR 镜像为 Public；GitHub Release 随私有仓库，仅仓库授权用户可访问。不公开源码或 Release。
- 用户选择“沿用当前签名密钥”：正式 APK 沿用已安装应用的签名身份，关闭调试功能，后续覆盖安装保留已有资料。
- 统一标签、安卓显示版本与内部版本号、后端运行包和镜像版本；普通提交不发布正式产物。
- 应用提供中文的检查更新、版本说明、下载进度和系统安装入口，检查及下载私有 Release 中的版本产物；不增加训练后端凭据。访问私有 Release 所需的客户端授权方式尚未由用户确认。
- 保留手动部署及 Compose `latest` 使用方式，复用 Gradle 缓存、共享模块构建结果和镜像依赖及素材分层。

## Capabilities

### New Capabilities

- `release-distribution`：标签控制的同版本 APK、私有仓库 Release 与公开 GHCR 镜像发布。
- `android-app-updates`：手机检查并下载有权限访问的正式版本，验证后发起保留资料的系统更新。

### Modified Capabilities

无。主规范目录尚无已归档规范；现有安卓训练与提醒行为保持不变。

## Impact

- 现有 GitHub Actions 发布工作流、仓库可见性与签名 Secrets。
- Gradle 版本与正式签名配置、服务端分发包、Dockerfile 和部署文档。
- 安卓设置页面、新增更新下载与安装实现、必要的系统安装授权和文件分享声明。
- OpenSpec 中本变更的仓库私有、GHCR 公开决定修正此前有关公开源码的描述；不改变单人使用、唯一业务 token、训练数据库或现有训练行为。
