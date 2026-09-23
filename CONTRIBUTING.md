# 参与贡献

感谢你愿意为 Pocket Agent 出力。提交 PR 前请先读一遍这份说明。

## 提 Issue 前

- 先搜索已有 issue，避免重复。
- 复现步骤、设备 / 系统版本、使用的 Provider 越具体越好。

## 开发环境

- JDK 17（`sourceCompatibility` / `jvmTarget` 均为 17）
- Android SDK Platform 37 与 Build-Tools 37，本地 Gradle 会在缺失时自动下载
- Gradle 用仓库自带的 wrapper，请勿全局安装其他版本：

```bash
./gradlew assembleDebug          # 构建 debug APK
./gradlew testDebugUnitTest     # 跑单元测试
./gradlew installDebug          # 安装到已连接设备
```

## 代码风格

- Kotlin 官方风格（`kotlin.code.style=official`），4 空格缩进，列宽约 120。
- 新文件 / 新功能配对应的单元测试；工具类（OCR、文档生成、SAF 封装）优先覆盖。
- 注释保持项目现有习惯：解释「为什么」而不是复述「做什么」，能用代码说清的地方不要写注释。

## 架构约束（请勿绕过）

这些是这个项目刻意保持的样子，PR 里请不要改动：

- 不引入 Hilt / Room，依赖装配维持手动的 `AppContainer` 模式。
- 网络层只用 OkHttp + kotlinx.serialization。
- API Key 只进 EncryptedSharedPreferences，DataStore 里仅存引用。
- 文件访问只走 SAF（`SafFileRepository`），不申请 `MANAGE_EXTERNAL_STORAGE`。
- 工具执行失败返回错误文本给模型，不抛异常崩溃。

## 提交流程

1. Fork 本仓库，从 `main` 切出功能分支（如 `feat/xxx`、`fix/xxx`）。
2. commit message 建议遵循 Conventional Commits（`feat:`、`fix:`、`docs:`、`refactor:` 等）。
3. 推送分支并开 PR，按模板填写改动说明与自检清单。
4. 一个 PR 只做一件事；顺手的大范围重构请另开 PR。

## 行为准则

参与本项目即表示你同意遵守 [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)。
