# weread-mcp

微信读书（WeRead）的独立 MCP 服务器。以 **Streamable HTTP** 传输在 `:8091/mcp` 提供工具，
可被 Claude Desktop 等任意 MCP 客户端、以及本仓库的 ai-service 直接复用。

## 功能

- **扫码登录**（微信读书网页版）：生成二维码 → 轮询状态 → 成功后持久化会话 Cookie。
  > 网页版扫码端点无公开文档（社区逆向），集中在 `weread_mcp/weread_client.py` 顶部常量，
  > 运行时若失效只需校准这一处；不可用时会降级为「二维码指向官方 Skill 页 + 回填 API Key」。
- **API Key 登录**（官方 Agent Gateway，推荐）：配置 `wrk-` 开头的个人 Key，
  走 `POST https://i.weread.qq.com/api/agent/gateway`，稳定可靠。
- **书架**：书名 / 作者 / 封面 / 分类 / 阅读进度 / 是否读完。
- **搜索**：按关键词搜书。

Key 获取：微信读书网页版 → 官方 Skill 页（`https://weread.qq.com/r/weread-skills`）→ 扫码登录 → 复制 `wrk-xxx`。

## 工具

| 工具 | 说明 |
|---|---|
| `login_qrcode()` | 发起扫码登录，返回 `{status, qrBase64(data:image/png), qrToken, message, expiresAt}` |
| `login_status()` | 当前登录态 + 进行中扫码进度（`loginQr` 字段） |
| `set_api_key(api_key)` | 用 `wrk-` Key 登录并持久化（校验失败返回 isError） |
| `logout()` | 清除本地凭据 |
| `get_bookshelf()` | 书架 `{total, readingCount, finishedCount, books[]}` |
| `search_books(keyword, count=10)` | 搜索 `{books[]}` |

统一输出 camelCase JSON；业务错误返回 `{"isError": true, "code", "message", "status_code"}`。

## 运行

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[dev]"

# 离线演示模式（假书架 + 自动成功的假二维码，不碰真实账号）
$env:WEREAD_MCP_FAKE = "true"
.\.venv\Scripts\python.exe -m weread_mcp

# 真实模式（先用上面的方式拿到 wrk- Key，再通过 set_api_key 登录）
.\.venv\Scripts\python.exe -m weread_mcp
```

配置（环境变量前缀 `WEREAD_MCP_`）：`HOST`(默认 127.0.0.1)、`PORT`(8091)、`FAKE`、`CREDENTIALS_PATH`(默认 `./data/credentials.json`)、`REQUEST_TIMEOUT_SECONDS`。

## 凭据安全

- 凭据（`wrk-` Key / 扫码 Cookie）只落盘在 `CREDENTIALS_PATH`，文件 0600，**绝不写日志**。
- 服务默认只监听 `127.0.0.1`；Docker 部署时位于内网。微信读书账号按「部署」绑定
  （一个 weread-mcp 实例 = 一个微信读书账号）。

## 测试

```powershell
.\.venv\Scripts\python.exe -m pytest -q
.\.venv\Scripts\python.exe -m ruff check weread_mcp tests
```
全部离线（MockTransport 伪造微信读书响应 / fake 模式）。
