# ChatModerator

基于大语言模型的 Minecraft（Java 版 / Paper/Bukkit）服务器聊天合规检测插件。

> 仓库名：`LLMChatGuard`，插件名：`ChatModerator`，主类：`com.chatmoderator.ChatModeratorPlugin`
> 构建目标：Paper 1.21.4 API（`api-version: 1.21`），Java 17+，Maven 打包。

插件依据**在线活跃管理员数量**与**在线玩家数**实时在「关闭 / 抽样 / 全量」三种检测模式间切换，调用可配置的大模型 API 对聊天做深度语义与混淆识别（零宽字符、同形字符、NFKC 等），命中后自动执行处罚并归档记录。同时内置日志滚动归档与轻量 Web 管理面板，且**支持完全自定义系统提示词**。

---

## 1. 功能特性

- **智能模式切换**：活跃 OP ≥ 2 或在线玩家 ≤ 1 时关闭检测；活跃 OP == 1 且在线 ≤ 3 时按概率抽样；活跃 OP == 0 且在线 > 3 时全量检测（详见 §5）。
- **大模型深度检测**：基于 `java.net.http.HttpClient` 的异步调用，支持信号量并发限流、超时、重试。
- **可自定义提示词**：提示词模板三级优先级（模型配置 > 全局 `custom_prompt_template` > 内置默认），通过 `{{BANNED_WORDS_JSON}}` 占位符注入词库。
- **自动处罚与记录**：命中后按 `punishment_command` 模板以控制台身份执行命令，并生成 `punishments/punish_<UUID>_<时间戳>.json` 结构化记录。
- **日志系统**：每聊天/检测/模式切换/处罚/错误均记录为 JSON 行，按小时滚动，每日 `log_archive_hour` 点打包 `logs/archive/YYYY-MM-DD.tar.gz`，按 `log_retention_days` 清理。
- **Web 管理面板**（内嵌 NanoHTTPD）：访客仅可查处罚记录，管理员登录后可查全部日志（聊天/检测结果）并支持多维筛选。
- **热重载**：`/chatmod reload` 运行时重载配置、词库、提示词与模型信息，无需重启。

---

## 2. 环境要求

- Java 17 或更高版本
- Paper / Spigot 服务端（API 1.21.x）；实测基于 Paper 1.21.4
- 可访问的大模型 API（OpenAI 兼容 chat/completions 接口，或其它支持 `top_k`/`thinking` 的接口）

> 说明：需求文档中 Minecraft 版本写为「26.1.2」，该版本号不符合既有命名体系。本实现按 Paper 1.21.4 API 构建；如需其它版本，改 `pom.xml` 中的 `paper-api` 版本与 `src/main/resources/plugin.yml` 的 `api-version` 即可。

---

## 3. 构建

```bash
mvn clean package -DskipTests
```

产物：`target/ChatModerator.jar`（约 3 MB，已将 NanoHTTPD / Gson / SnakeYAML / Commons-Compress / BCrypt 通过 shade 重定位到 `com.chatmoderator.lib.*`，避免与服务器其它插件冲突）。

> 若你的构建环境无法访问 `repo.papermc.io`（例如被全局 Maven 镜像用 `mirrorOf=*` 覆盖），请为 papermc 仓库配置直连或使用 `-s` 指定一份不覆盖该仓库的 `settings.xml`。

---

## 4. 安装与首次启动

1. 将 `target/ChatModerator.jar` 放入服务端 `plugins/` 目录。
2. 启动一次服务器。插件会在 `plugins/ChatModerator/` 下自动生成：
   ```
   config.yaml              # 主配置
   models/default.json      # 模型配置
   prompts/default_prompt.txt
   banned_words/base.txt    # 基础词库
   logs/                    # 运行日志（含 archive/）
   punishments/             # 处罚记录 JSON
   web/                     # 面板静态资源（可选，内置）
   ```
3. 编辑 `models/default.json`，填入真实的 `api_url`、`api_key`、`model_name`。
4. 按需编辑 `config.yaml`、`banned_words/*.txt`、`prompts/*.txt`。
5. 在游戏内执行 `/chatmod reload`（或重启服务器）使配置生效。
6. Web 面板默认 `127.0.0.1:8080`；`web_admin.password` **留空时 Web 面板不启动**（安全默认）。上线前务必设置强密码，建议使用 BCrypt 哈希（以 `$2a$`/`$2b$`/`$2y$` 开头）。

---

## 5. 检测模式决策（§4）

每次 `AsyncPlayerChatEvent` 即时计算：

| 模式 | 触发条件 | 行为 |
|---|---|---|
| **关闭 (STOP)** | 活跃 OP ≥ 2，**或** 在线玩家 ≤ 1 | 忽略所有消息，不调用 API |
| **抽样 (SAMPLE)** | 活跃 OP == 1 **且** 在线玩家 ∈ (1, 3] | 每条消息以 `sample_rate` 概率被检测 |
| **全量 (FULL)** | 活跃 OP == 0 **且** 在线玩家 > 3 | 所有消息全部提交大模型 |

未明确覆盖的组合（如活跃 OP==1 且在线>3）：无活跃 OP 时偏向 `FULL` 以保护，否则 `SAMPLE`。

- **活跃管理员**：`isOp() == true` 且未处于 AFK。
- **AFK 判定**：监听到移动/聊天/命令即刷新最后活跃时间；超过 `afk_timeout_minutes` 视为 AFK；玩家离线清除记录。
- 模式**切换**时记一条日志（非每条消息都记）。

---

## 6. 配置说明

### 6.1 `config.yaml`

```yaml
server_name: "My Server"
model: "default"                       # 对应 models/ 下文件名（不含 .json）
custom_prompt_template: "prompts/default_prompt.txt"  # 留空用内置默认

afk_timeout_minutes: 5
sample_rate: 0.5
sampling_strategy: "probability"       # 当前仅支持 probability

punishment_command: "/ban {playerName} 您触发了{serverName}违禁词自动检测系统..."

max_concurrent_requests: 5
retry_count: 0

# 批量检测调度（非调试模式）：聊天先入队，按固定间隔合并为一次模型请求
batch_interval_seconds: 5              # 每隔多少秒把队列里的对话批量发送给大模型检测
max_batch_size: 20                     # 每一批最多合并的消息条数
debug: false                           # true 时无视不检测条件，每句话立即单条检测（排查用）

failure_policy: "pass"                 # pass=解析失败/超时放行；local=用本地关键词兜底检测+处罚

log_retention_days: 7
log_archive_hour: 3                    # 每日该整点打包前一日日志

web_port: 8080
web_bind_address: "127.0.0.1"
web_admin:
  username: "admin"
  password: "hashed_or_plain"          # 建议 BCrypt 哈希
  session_timeout_minutes: 30
```

变量替换：`{playerName}`（已做 `; | &` 与换行清洗，防命令注入）、`{serverName}`、`{bannedWords}`、`{reason}`。

### 6.2 `models/default.json`

```json
{
  "model_id": "default",
  "api_url": "https://api.openai.com/v1/chat/completions",
  "api_key": "sk-...",
  "model_name": "gpt-4o",
  "max_tokens": 1024,
  "temperature": 0.2,
  "top_p": 1.0,
  "top_k": 0,
  "thinking": false,
  "rpm": 20,                            # 速率上限：批量发送时每分钟最多 rpm 次请求（令牌桶）
  "timeout_seconds": 120,               # 单次请求超时（秒）；流式模式下仅约束"连接+首字节"，主体按空闲超时读取
  "stream": false,                      # true=流式（SSE）输出，慢模型/思考模型建议开启，自动截去 <think:6124c78e>…</think:6124c78e>
  "system_prompt_template": "prompts/default_prompt.txt"
}
```

- `top_k`：仅当 **> 0** 时才会发送到 API（OpenAI 等不接受该字段，留 0 即不发送）。
- `thinking`：仅当 **true** 时发送思考开关（推理模型专用）。
- `thinking_param`：思考参数的**真实字段名**，因提供方而异。OpenAI/DeepSeek 用 `thinking`；**SiliconFlow（如 Qwen3）用 `enable_thinking`**。配错会导致 HTTP 400，服务端拒绝时本插件会自动降级（移除该参数后重试）。
- `timeout_seconds`：非流式下约束整次响应；流式下仅约束连接与首字节，主体按"空闲超时"读取，因此慢模型请开启 `stream: true` 并视情况调大此值。
- `system_prompt_template`：可选；不填则回退到 `config.yaml` 的 `custom_prompt_template`，再不填用内置默认提示词。

### 6.3 自定义提示词（关键特性）

插件在构造请求前，按优先级选出最终模板，把其中的占位符 `{{BANNED_WORDS_JSON}}` 替换为当前词库的 JSON 数组后作为 `system` 消息。

- 模板可自由改写角色设定、检测策略、输出格式；**必须保留 `{{BANNED_WORDS_JSON}}` 占位符**以保证词库注入。
- 默认输出约定（三行）：第 1 行行号 `1`，第 2 行 `True`/`False`，第 3 行 `{"banned_words":["词1","词2"]}`。解析器对行顺序做了容错（可缺失行号行）。
- 内置默认提示词已包含零宽字符移除、NFKC 规范化、同形字符映射、小写等标准化步骤。

---

## 7. 处罚与记录（§6）

当模型返回命中时：

1. 用 `punishment_command` 模板生成命令（变量安全替换）。
2. 以**控制台身份**在主线程执行（不阻塞游戏线程）。
3. 生成 `punishments/punish_<UUID>_<时间戳>.json`，含 `playerUuid / playerName / serverName / bannedWords / message / command / reason / timestamp`。

失败策略 `failure_policy`：
- `pass`：解析失败 / 超时 → 视为未命中（放行）。
- `local`：解析失败 / 超时 → 用本地关键词兜底检测，命中则处罚（记录实际命中词）。

## 7.1 批量检测、速率上限与调试模式

检测流程默认采用**批量**模式（需求新增）：

- **排队**：聊天消息经模式决策（STOP/SAMPLE/FULL）后，被选中的消息进入内存队列，不再逐条即时请求。
- **定时批量**：`DetectionManager` 每 `batch_interval_seconds` 秒从队列取出至多 `max_batch_size` 条，合并为**一次**模型请求（提示词改为要求返回与消息顺序对应的 JSON 数组），结果逐条回写日志/处罚/失败兜底。
- **速率上限（rpm）**：每次批量发送受 `models/*.json` 中 `rpm`（每分钟请求数）约束，采用令牌桶限速；若当前窗口已超限，本批消息退回队列、下个周期再试。
- **调试模式**：`config.yaml` 中 `debug: true` 时，**无视 STOP/抽样等不检测条件**，每句话立即以单条请求检测，便于排查提示词与模型行为。

> 提示：批量模式下处罚存在秒级延迟（最多约一个 `batch_interval_seconds`）。`/chatmod test` 与 `debug` 模式仍为即时单条检测。

---

## 8. 日志系统（§7）

- 记录内容：聊天原文（无论是否检测）、检测结果、模式切换、处罚动作、错误与重载。
- 滚动：`logs/chatmod-YYYY-MM-DD-HH.log`，每小时一个 JSON 行文件。
- 归档：每日 `log_archive_hour` 点将前一日小时日志打包 `logs/archive/YYYY-MM-DD.tar.gz` 并删除原文件。
- 清理：超过 `log_retention_days` 的 `.tar.gz` 自动删除。

---

## 9. Web 管理面板（§8）

内嵌 NanoHTTPD，绑定 `web_bind_address`、端口 `web_port`（设 0 禁用）。

| 路径 | 说明 |
|---|---|
| `GET /` | 仪表盘（访客看到统计卡片，管理员额外看到最近处罚） |
| `GET /punishments?player=&page=` | 处罚记录查询（访客可用，支持按玩家名搜索） |
| `GET /logs?type=chat\|detection&player=&from=&to=&result=` | 管理员日志查询（多维筛选、分页） |
| `POST /login` | 登录（表单字段 `username` / `password`） |
| `GET /logout` | 注销 |
| `/static/style.css` | 样式表 |

- 访客：仅可访问处罚记录。
- 管理员：登录后（BCrypt 校验，会话带过期时间并定时清理）可查看全部日志。
- 安全默认：`web_admin.password` 留空时 Web 面板**不启动**；会话 id 由密码学随机数生成，Cookie 标记 `HttpOnly; SameSite=Lax; Expires=...`；明文密码比对使用定长比较以降低时序侧信道。
- 生产建议：绑定 `127.0.0.1`，经反向代理 + HTTPS 对外；密码使用 BCrypt 哈希；绑定 `0.0.0.0` 会触发告警。

---

## 10. 游戏内命令（§10）

需 OP 权限（`chatmoderator.admin`）。

| 命令 | 说明 |
|---|---|
| `/chatmod reload` | 重载配置、词库、提示词模板与模型信息 |
| `/chatmod status` | 显示当前模式、在线人数、活跃 OP 数、队列状态 |
| `/chatmod test <player> <message>` | 模拟检测某玩家消息并返回结果（仅 OP） |

---

## 11. 文件结构

```
plugins/ChatModerator/
├── config.yaml
├── models/default.json
├── prompts/default_prompt.txt
├── banned_words/base.txt (+custom.txt 可多个，合并去重)
├── logs/chatmod-YYYY-MM-DD-HH.log
├── logs/archive/YYYY-MM-DD.tar.gz
├── punishments/punish_<UUID>_<ts>.json
└── web/（可选，内置）
```

源码包：`com.chatmoderator` 下 `config / afk / mode / words / prompt / model / listener / punishment / log / web / command` 及主类 `ChatModeratorPlugin`。

---

## 12. 故障排查

- **完全不拦截**：确认 `models/default.json` 的 `api_url/key/model_name` 正确；`failure_policy=pass` 时 API 失败会放行，可临时改 `local` 验证。
- **检测全部失败（HTTP 400）**：多为向 OpenAI 发送了 `top_k`/`thinking` 等未知字段，保持 `top_k: 0` 与 `thinking: false` 即可。
- **管理员无法登录**：确认 `web_admin.password` 已设置（留空时面板不启动）；为明文或正确 BCrypt 哈希；端口 `web_port` 未被占用、绑定地址可达。
- **日志爆量**：已将模式日志记录收敛到「仅切换时」；聊天日志按设计全量记录。
- **词库不生效**：确认 `banned_words/*.txt` 每行一词；`/chatmod reload` 后生效。

---

## 13. 安全建议

- Web 面板仅限本地或可信网段，生产环境加反向代理与 HTTPS。
- 管理密码使用 BCrypt 哈希存储，避免明文。
- 处罚命令仅以控制台身份执行，玩家名已清洗命令分隔符，防注入。

---

## 14. 许可

见仓库 `LICENSE`。
