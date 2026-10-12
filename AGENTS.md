# Aegis Fork 项目规则

## 项目与分支

- 本仓库是 Aegis Authenticator 的 Android/Java fork。`origin` 指向本 fork，`upstream` 指向 `beemdevelopment/Aegis`。
- `master` 仅跟随官方 `upstream/master`，不要在 `master` 上提交自定义功能。本 fork 的 SFTP 工作放在 `feature/sftp-backup` 或由它延伸的功能分支；合并上游前先检查差异，禁止强推或覆盖用户修改。
- SFTP 功能范围是加密保险库上传、远端版本列表、用户主动下载与完整恢复。不要加入多设备自动双向合并或静默覆盖本地保险库。

## 代码位置与约束

- Android 应用在 `app/`；主要 Java 代码在 `app/src/main/java/com/beemdevelopment/aegis/`，界面资源在 `app/src/main/res/`，单元测试在 `app/src/test/`，设备测试在 `app/src/androidTest/`。
- `vault/VaultManager.java` 负责保存及触发备份，`vault/VaultBackupManager.java` 负责现有 Storage Access Framework 备份，`AegisBackupAgent.java` 负责 Android 系统备份。新增 SFTP 网络工作保持独立，不放进 Android BackupAgent。
- 复用现有 `VaultFile`/`VaultRepository` 的加密导出格式。远端只能存储可用密码恢复的加密备份；严格校验 SSH Host Key，保护 SSH 密码与私钥，日志不得包含凭据或验证码明文。
- 现有 `ImportEntriesActivity` 用于追加条目，不等同于完整保险库恢复。恢复已有保险库前须有可验证的回退副本；首次安装恢复须能从引导流程进入。
- 保持对上游的改动面小；优先新增独立 SFTP 模块，只在保存和设置入口接线。

## 开发与验证

- 本环境使用原生 Windows 和 PowerShell。优先使用仓库配置的 mise 工具链，以及 `gradlew.bat`；不要依赖机器上碰巧存在的其他全局 JDK。
- 修改前查看相关源码和最近的 `AGENTS.md`；编辑文件使用 `apply_patch`，保留无关改动。搜索优先用 `rg`。
- 涉及 Android 代码时，按改动范围运行 Debug 构建、相关单元测试和 lint；网络备份还需验证断网、认证失败、Host Key 变化、上传中断与全新设备恢复。不能运行的检查说明原因。
- CodeGraph 项目索引位于本地 `.codegraph/`。涉及调用关系或改动影响时可先用 CodeGraph MCP 或 `codegraph explore`、`codegraph impact` 查找，再直接阅读源码核对；索引过期时运行 `codegraph sync`。

## 文档与本地文件

- 规划、设计和执行记录放在 `docs/superpowers/`。带日期的 Markdown 使用 `YYYY-MM-DD-中文主题.md`；该目录只有 `README.md` 允许 Git 跟踪，其余文件不得加入暂存区。
- 浏览器截图、快照及调试输出放在仓库根目录 `.temp/`，不得提交。不要暂存本地 `mise.toml`、凭据、私钥、签名材料或保险库备份。
