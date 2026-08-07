"""weread-mcp —— 微信读书的独立 MCP 服务器。

以 Streamable HTTP 传输在 /mcp 提供工具，可被 Claude Desktop 等 MCP 客户端
以及本仓库的 ai-service 直接复用。凭据（官方 Agent Gateway 的 wrk- Key 或
扫码登录得到的 Cookie）只落盘在 WEREAD_MCP_CREDENTIALS_PATH，不写日志。
"""

from .server import build_mcp

__all__ = ["build_mcp"]
