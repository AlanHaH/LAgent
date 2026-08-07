"""FastMCP 服务器入口与工具注册。

工具逻辑以模块级函数实现（可单测），FastMCP 只做薄包装。工具返回统一用
camelCase dict；业务错误返回 `{"isError": true, "code", "message", "status_code"}`
的结构化 dict（不依赖 FastMCP 对异常的 isError 序列化行为），ai-service 侧统一解析。
"""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Any

from mcp.server.fastmcp import FastMCP

from .config import Settings
from .fake import FakeWereadHttpClient
from .qr_login import QrLoginManager
from .storage import CredentialStore
from .weread_client import WereadError, WereadHttpClient

_Client = WereadHttpClient | FakeWereadHttpClient


def _now() -> str:
    return datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z")


def _error(code: str, message: str, status_code: int) -> dict[str, Any]:
    return {"isError": True, "code": code, "message": message, "status_code": status_code}


def _empty_base() -> dict[str, Any]:
    return {
        "loggedIn": False,
        "loginType": None,
        "nickname": None,
        "headImgUrl": None,
        "isVip": False,
        "lastLoginAt": None,
    }


async def handle_login_status(client: _Client, qr: QrLoginManager) -> dict[str, Any]:
    try:
        base: dict[str, Any] = await client.login_status()
    except WereadError:
        base = _empty_base()
    qr_view = await qr.poll() if qr.active else None
    if qr_view is not None and qr_view["status"] == "SUCCESS":
        base = await client.login_status()  # 成功后重新读取已落库的凭据
    return {**base, "loginQr": qr_view}


async def handle_login_qrcode(client: _Client, qr: QrLoginManager) -> dict[str, Any]:
    try:
        base = await client.login_status()
    except WereadError:
        base = _empty_base()
    if base.get("loggedIn"):
        return {
            "status": "SUCCESS",
            "qrBase64": None,
            "qrToken": None,
            "message": "已登录",
            "expiresAt": None,
        }
    try:
        return await qr.create()
    except WereadError as exc:
        return _error(exc.code, exc.message, exc.status_code)


async def handle_set_api_key(client: _Client, store: CredentialStore, api_key: str) -> dict[str, Any]:
    try:
        info = await client.verify_api_key(api_key)
    except WereadError as exc:
        return _error(exc.code, exc.message, exc.status_code)
    creds = store.load()
    creds.pop("cookie", None)
    creds.update(
        {
            "loginType": "API_KEY",
            "apiKey": api_key,
            "nickname": info.get("nickname"),
            "headImgUrl": info.get("headImgUrl"),
            "lastLoginAt": _now(),
        }
    )
    store.persist(creds)
    return {
        "loggedIn": True,
        "loginType": "API_KEY",
        "nickname": info.get("nickname"),
        "headImgUrl": info.get("headImgUrl"),
        "isVip": bool(info.get("isVip") or False),
        "lastLoginAt": _now(),
        "loginQr": None,
    }


async def handle_logout(store: CredentialStore, qr: QrLoginManager) -> dict[str, Any]:
    store.clear()
    qr.clear()
    return {"loggedIn": False}


async def handle_get_bookshelf(client: _Client) -> dict[str, Any]:
    try:
        books = await client.shelf_sync()
    except WereadError as exc:
        return _error(exc.code, exc.message, exc.status_code)
    return {
        "total": len(books),
        "readingCount": sum(1 for book in books if book["status"] == "reading"),
        "finishedCount": sum(1 for book in books if book["isFinished"]),
        "books": books,
    }


async def handle_search(client: _Client, keyword: str, count: int) -> dict[str, Any]:
    try:
        books = await client.search(keyword, count)
    except WereadError as exc:
        return _error(exc.code, exc.message, exc.status_code)
    return {"books": books}


async def handle_book_info(client: _Client, book_id: str) -> dict[str, Any]:
    try:
        return await client.book_info(book_id)
    except WereadError as exc:
        return _error(exc.code, exc.message, exc.status_code)


async def handle_book_progress(client: _Client, book_id: str) -> dict[str, Any]:
    try:
        return await client.book_progress(book_id)
    except WereadError as exc:
        return _error(exc.code, exc.message, exc.status_code)


async def handle_recommend(client: _Client, count: int) -> dict[str, Any]:
    try:
        return await client.recommend(count)
    except WereadError as exc:
        return _error(exc.code, exc.message, exc.status_code)


async def handle_similar(client: _Client, book_id: str, count: int) -> dict[str, Any]:
    try:
        return await client.similar(book_id, count)
    except WereadError as exc:
        return _error(exc.code, exc.message, exc.status_code)


async def handle_readdata_detail(client: _Client, mode: str) -> dict[str, Any]:
    try:
        return await client.readdata_detail(mode)
    except WereadError as exc:
        return _error(exc.code, exc.message, exc.status_code)


def register_tools(mcp: FastMCP, client: _Client, qr: QrLoginManager, store: CredentialStore) -> None:
    @mcp.tool()
    async def login_qrcode() -> dict[str, Any]:
        """发起扫码登录，返回二维码（BookQrLoginView）。"""
        return await handle_login_qrcode(client, qr)

    @mcp.tool()
    async def login_status() -> dict[str, Any]:
        """当前登录状态与进行中的扫码进度（BookLoginStatusView）。"""
        return await handle_login_status(client, qr)

    @mcp.tool()
    async def set_api_key(api_key: str) -> dict[str, Any]:
        """用官方 Agent Gateway 个人 Key（wrk- 开头）登录并持久化。"""
        return await handle_set_api_key(client, store, api_key)

    @mcp.tool()
    async def logout() -> dict[str, Any]:
        """清除本地微信读书凭据。"""
        return await handle_logout(store, qr)

    @mcp.tool()
    async def get_bookshelf() -> dict[str, Any]:
        """返回当前账号书架（BookShelfView）。"""
        return await handle_get_bookshelf(client)

    @mcp.tool()
    async def search_books(keyword: str, count: int = 10) -> dict[str, Any]:
        """按关键词搜索书。"""
        return await handle_search(client, keyword, count)

    @mcp.tool()
    async def book_info(book_id: str) -> dict[str, Any]:
        """获取书籍基本信息（书名/作者/简介/出版社/评分等）。"""
        return await handle_book_info(client, book_id)

    @mcp.tool()
    async def book_progress(book_id: str) -> dict[str, Any]:
        """获取用户对某本书的精确阅读进度（百分比/章节/阅读时长）。"""
        return await handle_book_progress(client, book_id)

    @mcp.tool()
    async def recommend_books(count: int = 12) -> dict[str, Any]:
        """获取个性化推荐书籍。"""
        return await handle_recommend(client, count)

    @mcp.tool()
    async def similar_books(book_id: str, count: int = 12) -> dict[str, Any]:
        """获取与某本书相似的推荐书籍。"""
        return await handle_similar(client, book_id, count)

    @mcp.tool()
    async def readdata_detail(mode: str = "overall") -> dict[str, Any]:
        """获取用户阅读统计数据（mode: weekly/monthly/annually/overall）。"""
        return await handle_readdata_detail(client, mode)


def build_mcp(settings: Settings) -> FastMCP:
    store = CredentialStore(settings.credentials_path)
    if settings.fake:
        client: _Client = FakeWereadHttpClient(store)
    else:
        client = WereadHttpClient(store, timeout=settings.request_timeout_seconds)
    qr = QrLoginManager(client)
    mcp = FastMCP("weread-mcp")
    register_tools(mcp, client, qr, store)
    return mcp


def main() -> None:
    settings = Settings()
    mcp = build_mcp(settings)
    # mcp 1.x 起 host/port 通过 settings 配置（FastMCP.run 不再接受 host/port 参数）。
    mcp.settings.host = settings.host
    mcp.settings.port = settings.port
    # Streamable HTTP 传输，默认端点 /mcp（mcp 1.x 内部起 uvicorn）。
    mcp.run(transport="streamable-http")
