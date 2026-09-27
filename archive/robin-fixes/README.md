# 归档：`robin-custom-headers` fork 的 6 个 commit

这些补丁原本只存在于 `/tmp/robin-backup`（机器重启或 `/tmp` 清理即丢失）。
fork 本身待删除（删仓需 `delete_repo` scope，只能交互式授权），故先把成果固化到本分支。

## 来源

- 原 fork：`Xiaobei09/robin-custom-headers`（**待删除**）
- 上游基线：`antongulin/robin`（官方 Robin action）
- 导出方式：`git format-patch -6`，故可原样重放到上游。

## 哪些值得回上游（与 Silicon 项目无关的通用修复）

| 补丁 | 价值 |
|---|---|
| `0005-fix-log-the-real-error-before-the-stream-path-masks-` | **通用**。流式路径会把真实错误掩盖成「长时间无响应」，此补丁在掩盖前先记录真实错误。排障价值高。 |
| `0006-chore-opt-in-ROBIN_DEBUG_HEADERS` | **通用**。可选开关，dump 实际发出的请求头。接入新上游时定位准入问题很有用。 |
| `0001` / `0002` / `0004` | 编译/作用域修正，属本 fork 私有实现的必要修补，随实现一起废弃。 |
| `0003-feat-force-stream-and-compat-tools` | 针对免费档的绕过手段（强制 stream、伪造 tools），**不建议回上游**——依赖具体厂商的兼容行为。 |

## 当前架构已不需要 fork

`test` 分支的 `robin.yml` 改用 **zengate 本地网关**（网关内跑真实 opencode 进程访问
`big-pickle`），全仓 `.github/` 已无任何 `secrets.*` 引用，因此**不需要** custom headers、
force-stream、compat-tools 这些针对直连厂商的兼容手段——真实 opencode 客户端天然满足
`stream` / `tools` / `X-Session-ID` / `User-Agent` 四道闸门。

本分支仅作留档，不参与任何构建或工作流。
