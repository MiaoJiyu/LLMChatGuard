# 快速启动指南

5 步跑起 ChatModerator 聊天合规检测插件。

## 1. 构建

```bash
mvn clean package -DskipTests
# 产物：target/ChatModerator.jar
```

## 2. 安装并首次启动

```bash
cp target/ChatModerator.jar <你的服务端>/plugins/
# 启动服务器一次，自动生成 plugins/ChatModerator/ 配置目录
```

## 3. 填写模型凭据

编辑 `plugins/ChatModerator/models/default.json`：

```json
{
  "api_url": "https://api.openai.com/v1/chat/completions",
  "api_key": "sk-你的真实密钥",
  "model_name": "gpt-4o",
  "max_tokens": 1024,
  "temperature": 0.2,
  "top_p": 1.0,
  "top_k": 0,
  "thinking": false,
  "timeout_seconds": 120,
  "stream": false
}
```

> `top_k` 保持 `0`、`thinking` 保持 `false` 可兼容 OpenAI；接入支持这些字段的接口时再按需开启。
> 若使用 **SiliconFlow（如 Qwen3）** 并要开启思考，需设 `"thinking": true` 且 `"thinking_param": "enable_thinking"`，否则会因未知参数 `thinking` 被拒（HTTP 400）；本插件会在被拒时自动降级。
> 模型响应较慢（含思考过程）时，把 `stream` 改为 `true`，并将 `timeout_seconds` 调大（如 180），可避免因整体超时失败。

## 4. 维护词库与密码

- 编辑 `plugins/ChatModerator/banned_words/base.txt`，每行一个违禁词。
- 设置 `config.yaml` 中的 `web_admin.password`（建议 BCrypt 哈希，以 `$2a$` 开头）。**留空则 Web 面板不启动**（安全默认），上线前务必填写。
- 自定义检测逻辑可改 `prompts/default_prompt.txt`，**保留 `{{BANNED_WORDS_JSON}}` 占位符**。

## 5. 生效与验证

```text
/chatmod reload        # 重载配置
/chatmod status        # 查看当前模式 / 在线人数 / 活跃 OP
/chatmod test <玩家> <消息>   # OP 模拟检测，立即看到命中结果
```

浏览器打开 `http://127.0.0.1:8080` 登录 Web 面板，查看处罚记录与日志。

---

### 触发检测的典型场景

- 在线玩家 ≤ 1 或活跃 OP ≥ 2 → **关闭检测**（不调 API）。
- 活跃 OP == 1 且在线 ≤ 3 → **抽样检测**（按 `sample_rate` 概率）。
- 活跃 OP == 0 且在线 > 3 → **全量检测**。

详见 [`README.md`](./README.md)。
