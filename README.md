# Pocket Agent

[![Android CI](https://github.com/wb497516281-cyber/PocketAgent-github/actions/workflows/android.yml/badge.svg)](https://github.com/wb497516281-cyber/PocketAgent-github/actions/workflows/android.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-violet.svg)](LICENSE)

一个轻量的个人自用 Android Agent App。用 Kotlin + Jetpack Compose 编写，接入任意
OpenAI 兼容的大模型 API，并通过系统「存储访问框架（SAF）」在你选定的目录里创建、读取、
写入、删除文件。单一 App、无后端代理、无账号体系，只做本地的事。

## 技术栈

- Kotlin、Jetpack Compose（Material 3）、Coroutines/Flow
- OkHttp（含 logging-interceptor）、kotlinx.serialization
- DataStore Preferences（Provider 配置、目录 URI、会话历史）
- EncryptedSharedPreferences（API Key 加密存储）
- DocumentFile / SAF（文件访问，不申请任何存储权限）
- Google ML Kit 中文文本识别（本地 OCR，图片兜底）
- Navigation Compose、lifecycle-viewmodel-compose
- 手动 `AppContainer` 依赖注入，不用 Hilt
- `minSdk 26`，`compileSdk / targetSdk 37`

## 构建

需要 Android SDK（Platform 37、Build-Tools 37，Gradle 会在缺失时自动下载）。

```bash
./gradlew assembleDebug          # 产出 app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # 运行单元测试
```

需要一个 JDK 17 并把 `JAVA_HOME` 指到它（Windows 用 `gradlew.bat`，Linux / macOS 用
`./gradlew`）。仓库里已带 Gradle Wrapper，无需另外装 Gradle。

安装到设备后首次启动会进入「对话」页。

## 项目结构

```
app/src/main/java/com/pocket/agent/
├── data/settings/     ProviderConfig、ProviderPresets（内置服务商）、
│                      SettingsRepository（DataStore）、ApiKeyStore（加密）
├── data/file/         SafFileRepository（SAF 封装）与数据模型
├── data/chat/         ChatMessage（含图片附件）/ ChatSessionStore（会话历史）
├── data/image/        ImageCompressor（Photo Picker 选图后本地采样压缩）
├── llm/               LlmProvider、OpenAiCompatibleProvider、SseParser、Provider 工厂
├── agent/             AgentEngine、ToolRegistry、AgentTool、FileTools、SystemPrompt、
│                      ImagePreprocessor + ImageTextExtractor（OCR 降级）
├── agent/tools/       DocumentTools（create_pdf / create_word / create_ppt）与
│                      MarkdownBlocks、PdfWriter、DocxWriter、PptxWriter
├── ui/chat/           ChatScreen + ChatViewModel（流式输出、工具调用卡片、确认弹窗）
├── ui/settings/       SettingsScreen + SettingsViewModel（Provider 增删改、测试连接）
├── ui/files/          FilesScreen + FilesViewModel（选目录、浏览、新建）
├── util/              AppContainer（手动 DI）、FileNames（文件名校验）
└── ui/theme/          主题
```

## 配置一个模型 Provider

1. 进「设置」页，点右下角「添加 Provider」。
2. 先选服务商。已内置：DeepSeek、阿里百炼、通义千问、Kimi、智谱 GLM、MiniMax、硅基流动、零一万物、
   OpenAI、OpenRouter、Google Gemini、xAI Grok、Groq、Mistral、Together AI。选中后 Base URL
   与默认模型自动填好；不在列表里的端点（One API、自建网关等）点「自定义」手动填。
   - 阿里百炼与通义千问共用同一个入口 `https://dashscope.aliyuncs.com/compatible-mode/v1`，
     只是名称不同：要用百炼控制台的 Key 调它托管的模型（如 `deepseek-r1`、`deepseek-v3`）
     就选「阿里百炼」，只调 qwen 系列选两者皆可。
3. 填 API Key（只写进 EncryptedSharedPreferences，DataStore 里仅保存一个引用）。附加请求头每行
   一条「名称: 值」，OpenRouter 等会用到。内置服务商还会带一列「常用模型」芯片，点一下即可填入。
4. 模型名有两种填法：
   - 点「获取模型」，App 会请求 `{baseUrl}/models`，把拿到的列表过滤掉 embedding、vision、
     audio、dall-e、whisper 等非文本模型后显示为下拉框，选中即设为当前模型；「刷新」可重新拉取，
     列表同时缓存进 DataStore，下次进编辑器、重启 App 都能直接用，不会再发请求。
    - 端点没有 `/models`（返回 404/405/501 等）或拉取失败时，下拉框不出现，改回手动输入，并提示
      「该供应商不支持自动获取模型，请手动输入」。
    - 模型名下方有「支持视觉输入」勾选框。选中或切换模型时会按模型名里的关键字（`4o`、`claude-3`、
      `vl`、`gemini`、`gpt-4.1`、`gpt-5`、`o3` 等）自动判断，手动勾选过就以你的选择为准。
      勾上的模型才能直接看懂图片，没勾的走本地 OCR 兜底（见下文）。
5. 点「测试并保存」确认能通（会发一次非流式 ping）。保存下来的第一个 Provider 会自动设为默认。
6. 之后在列表里点「设为默认」即可切换当前对话使用的模型；「测试连接」可随时复检。

Base URL 末尾的 `/` 会被去掉，请求统一打到 `{baseUrl}/chat/completions`。

## 选择保存目录

进「文件」页，点「选择目录」，用系统目录选择器挑一个目录。授权通过
`takePersistableUriPermission` 持久化，重启后依然有效。App 只能读写这个目录，
不申请 `MANAGE_EXTERNAL_STORAGE` 或任何存储权限。

## Agent 与文件工具

对话页把 system prompt + 历史 + 工具列表发给模型；文本流式回显，遇到工具调用就执行，
结果以 `role=tool`、`tool_call_id` 回填后再问模型，最多 8 步，没有工具调用即结束。
工具失败会以错误文本回给模型而不是崩溃。内置工具：

`get_current_directory`、`list_files`、`create_file`、`read_file`、`write_file`、`delete_file`，
联网搜索工具 `web_search`，以及文档生成工具 `create_pdf`、`create_word`、`create_ppt`，
后两类分别见下文。

- `create_file` 参数：`file_name`、`content`、`mime_type`（默认 `text/plain`）。
- 覆盖、删除都会先弹确认框，用户拒绝则不修改。
- 文件名过滤 `/`、`\`、`..`、空字符、控制字符、Windows 保留名、结尾的 `.`/空格，
  单文件上限 1 MB。

试试在对话里输入：

> 在当前目录创建 note.md，内容写测试成功

Agent 会调用 `create_file` 完成创建，并总结结果。

## 联网搜索

在「设置」页底部的「联网搜索配置」里填 Tavily API Key（在 [tavily.com](https://tavily.com) 申请，
免费额度即可试用），Key 同样只进 EncryptedSharedPreferences，DataStore 只存引用。

填好后模型多出一个 `web_search` 工具，参数只有 `query`。System Prompt 明确要求：需要最新信息或
对事实不确定时，先调用 `web_search` 拿到结果再作答。工具只取前 3 条结果，每条摘要截断到 500 字，
以 JSON（`title`/`url`/`content`）回给模型。没配 Key、Key 失效、超时或返回无法解析时，工具返回
错误文本而不是抛异常，模型会如实说明搜不了。

试试在对话里输入：

> 今天有什么 AI 新闻

Agent 会自动调用 `web_search`，总结前几条搜索结果。

## 图片输入与 OCR 兜底

输入框右侧的图片按钮调起系统 Photo Picker（不需要任何权限），选中的图片在本地先瘦身再发出：
BitmapFactory 采样把长边压到 2048 以内，再按 JPEG 质量降到 1 MB 以下，然后转 Base64 成为
`ChatMessage.images` 里的附件。压缩全程在本机完成，第三方看不到原图。

能否直接看懂图片取决于当前模型的 `supportsVision` 标记：

- 勾了「支持视觉输入」的模型，按 OpenAI 兼容格式把 `content` 组装成数组：先一段 `type=text`，
  再每个附件一段 `type=image_url`，内容是 `data:image/jpeg;base64,...` 的 Data URL。
- 没勾的模型走本地兜底：`ImagePreprocessor` 先用 ML Kit 中文模型把每张图的文字识别出来，
  以 `[图片OCR结果：…]` 拼进 Prompt，并去掉图片附件，改发纯文本。聊天记录里会插一条系统提示
  「当前模型不支持视觉，已自动提取图片文字发送。」。
- 模型不支持视觉、图片里又没有文字时不会白跑一轮：直接提示切换到支持视觉的模型再试。

试试发一张带文字的截图，再切到勾了视觉的模型重试同一张图。

## 文档生成（PDF / Word / PPT）

System Prompt 把模型定位成排版专家：要生成文档时先规划结构，再调用对应工具，不要把正文直接写成
Markdown 代码块。三个工具都写进你授权的 SAF 目录，覆盖已有文件前照旧弹确认框。

`create_pdf`、`create_word`、`create_ppt`，参数分别如下：

- `create_pdf`：`file_name`（自动补 `.pdf`）、`markdown_content`，支持 `#` 标题、`-` 列表、
  代码块，按 A4 排版并自动分页。
- `create_word`：`file_name`（自动补 `.docx`）、`content_json`，形如
  `{"title":"...","paragraphs":[{"text":"...","heading":1-3}],"tables":[{"headers":[...],"rows":[[...]]}]}`。
- `create_ppt`：`file_name`（自动补 `.pptx`）、`slides_json`，形如
  `{"slides":[{"title":"...","subtitle":"...","bullets":["..."],"layout":"cover | section | content | end","notes":"...","images":[{"url":"...","caption":"..."}]}]}`：
  - `layout` 决定版式，未声明时第一页按封面、最后一页按结束页处理；每页 3-6 条要点、
    每条不超过 50 字效果最好。
  - `subtitle` 只在封面和结束页显示；`notes` 写进演讲者备注。
  - `images` 给每页插图，每页最多 3 张，图放在正文右侧或封面右半，`caption` 显示在图下方。
    图片来源两种：`url` 填 http(s) 地址（可以让 `web_search` 先找一张），或 `attachment` 填本次
    对话里你上传图片的序号（从 1 开始），二者填一个即可。
  - 图片下载失败或超出每页上限时，那一张会被跳过，原因作为说明文字回给模型，其余页面照常生成。

渲染全部在本地完成，没有引入 Apache POI：PDF 用平台自带的 `PdfDocument` 配合 `StaticLayout`
按 A4 排版、自动分页；`.docx` 与 `.pptx` 则用 `java.util.zip` 直接写出 WordprocessingML 与
PresentationML 包（Word 需要的最小部件集：正文、样式、关系、文档属性）。参数缺失、JSON 解析失败、
内容为空、目录没选、渲染异常，都会换成一句错误文本回给模型，不会让会话崩掉。

生成期间工具卡片显示「文档生成中」，成功后卡片下方多一个「打开文件」按钮，用系统 Intent
（`ACTION_VIEW`）把文件交给能预览的应用。

试试在对话里输入：

> 帮我写一份 Android 开发周报，保存为 PDF

## 说明

- 仅声明 `INTERNET` 权限；`networkSecurityConfig` 只对 localhost/127.0.0.1/10.0.2.2
  放开明文，其它地址必须是 https。
- 会话历史与 Provider 配置存在本地 DataStore，API Key 单独加密存储。

## 参与贡献

欢迎提 Issue 和 PR。构建命令、代码风格和架构约束（不引入 Hilt / Room、网络层只用
OkHttp + kotlinx.serialization、文件访问只走 SAF）见 [CONTRIBUTING.md](CONTRIBUTING.md)，
社区行为准则见 [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)。

## 开源许可

本项目基于 [MIT License](LICENSE) 发布，转载请保留版权声明。
