"""ai-service → weread-mcp 的 MCP 客户端（Streamable HTTP）。

每次调用使用独立的 `async with` 作用域（initialize → call_tool → 关闭）。扫码轮询约每
2.5s 一次，握手开销在本机可忽略；作用域式会话经实测可复用多次 call_tool，且避免了
手动 `__aenter__`/`__aexit__` 复用会话时 anyio cancel scope 跨任务退出的挂起问题。
任一调用失败重试一次，仍失败映射为 `AI_DEPENDENCY_UNAVAILABLE`。mcp 依赖在首次使用时
惰性导入，保证离线测试（注入 FakeWereadMcpClient）不依赖 mcp 包安装。
"""

from __future__ import annotations

import json
import logging
from typing import Any

import httpx

from app.config import Settings
from app.core.errors import ServiceError

logger = logging.getLogger(__name__)


class WereadMcpClient:
    def __init__(self, settings: Settings) -> None:
        self._url = settings.weread_mcp_url
        self._timeout = settings.weread_mcp_timeout_seconds
        # trust_env=False：本机可能开着 Clash 等系统代理，httpx 默认会把它读进来，
        # 导致到 127.0.0.1:8091 的 MCP 请求被代理转发而返回 502/挂起。MCP 端点是内网
        # 服务，永远不该走系统代理。
        self._http = httpx.AsyncClient(timeout=self._timeout, trust_env=False)

    async def _invoke_once(self, tool_name: str, arguments: dict[str, Any]) -> Any:
        # 惰性导入：mcp 未安装时离线路径照常工作。
        from mcp import ClientSession
        from mcp.client.streamable_http import streamable_http_client

        async with streamable_http_client(url=self._url, http_client=self._http) as streams:
            read, write = streams[0], streams[1]
            async with ClientSession(read, write) as session:
                await session.initialize()
                return await session.call_tool(tool_name, arguments)

    async def _call(self, tool_name: str, arguments: dict[str, Any] | None = None) -> dict[str, Any]:
        for attempt in (0, 1):
            try:
                result = await self._invoke_once(tool_name, arguments or {})
                return self._parse_result(result)
            except ServiceError:
                raise
            except Exception as exc:  # noqa: BLE001 - 统一映射为依赖不可用
                logger.warning("weread-mcp call failed attempt=%s tool=%s error=%s", attempt, tool_name, exc)
                if attempt == 1:
                    raise ServiceError(
                        "AI_DEPENDENCY_UNAVAILABLE",
                        "微信读书服务暂不可用，请稍后重试",
                        status_code=503,
                        retryable=True,
                    ) from exc
        raise ServiceError(
            "AI_DEPENDENCY_UNAVAILABLE", "微信读书服务暂不可用", status_code=503, retryable=True
        )

    @staticmethod
    def _parse_result(result: Any) -> dict[str, Any]:
        """解析 MCP call_tool 返回。业务错误（isError 或结构化 dict）映射为 ServiceError。"""
        texts = [block.text for block in result.content if getattr(block, "type", None) == "text"]
        if not texts:
            raise ServiceError("WEREAD_REMOTE_ERROR", "微信读书服务返回异常", status_code=502)
        try:
            payload = json.loads(texts[0])
        except (json.JSONDecodeError, TypeError) as exc:
            raise ServiceError("WEREAD_REMOTE_ERROR", "微信读书服务返回异常", status_code=502) from exc
        if not isinstance(payload, dict):
            raise ServiceError("WEREAD_REMOTE_ERROR", "微信读书服务返回异常", status_code=502)
        if payload.get("isError"):
            code = str(payload.get("code") or "WEREAD_REMOTE_ERROR")
            status_code = int(payload.get("status_code") or 502)
            raise ServiceError(
                code,
                str(payload.get("message") or "微信读书返回错误"),
                status_code=status_code,
                retryable=status_code >= 500,
            )
        return payload

    async def login_status(self) -> dict[str, Any]:
        return await self._call("login_status")

    async def login_qrcode(self) -> dict[str, Any]:
        return await self._call("login_qrcode")

    async def set_api_key(self, api_key: str) -> dict[str, Any]:
        # 工具入参为 FastMCP 的 snake_case 命名（见 weread-mcp/server.py）
        return await self._call("set_api_key", {"api_key": api_key})

    async def logout(self) -> dict[str, Any]:
        return await self._call("logout")

    async def get_bookshelf(self) -> dict[str, Any]:
        return await self._call("get_bookshelf")

    async def search_books(self, keyword: str, count: int) -> dict[str, Any]:
        return await self._call("search_books", {"keyword": keyword, "count": count})

    async def book_info(self, book_id: str) -> dict[str, Any]:
        return await self._call("book_info", {"book_id": book_id})

    async def book_progress(self, book_id: str) -> dict[str, Any]:
        return await self._call("book_progress", {"book_id": book_id})

    async def readdata_detail(self, mode: str) -> dict[str, Any]:
        return await self._call("readdata_detail", {"mode": mode})

    async def recommend_books(self, count: int) -> dict[str, Any]:
        return await self._call("recommend_books", {"count": count})

    async def similar_books(self, book_id: str, count: int) -> dict[str, Any]:
        return await self._call("similar_books", {"book_id": book_id, "count": count})
