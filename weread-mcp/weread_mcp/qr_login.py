"""扫码登录状态机（内存态，单租户，进程重启即失效可接受）。

状态流转：PENDING →(扫码) SCANNED →(手机确认) SUCCESS(持久化 Cookie)；超时→EXPIRED。
"""

from __future__ import annotations

import base64
import io
import time
import uuid
from datetime import UTC
from typing import Any, Protocol

# 运行时用到，避免导入链循环
from .weread_client import WereadError


def render_qr_png(text: str, box_size: int = 8) -> str:
    """把任意文本渲染成二维码 PNG，返回 data:image/png;base64,...。"""
    import qrcode

    img = qrcode.make(text, box_size=box_size)
    buffer = io.BytesIO()
    img.save(buffer, format="PNG")
    return "data:image/png;base64," + base64.b64encode(buffer.getvalue()).decode("ascii")


class WereadClientLike(Protocol):
    """qr_login 依赖的客户端协议（真实 / fake 都实现）。"""

    async def qr_create(self) -> dict[str, Any]: ...
    async def qr_poll(self, qr_token: str) -> dict[str, Any]: ...
    async def persist_qr_login(self, user: dict[str, Any] | None, cookie: dict[str, str]) -> None: ...


class QrLoginSession:
    def __init__(self, qr_token: str, qr_base64: str, expires_in: int) -> None:
        self.id = uuid.uuid4().hex
        self.qr_token = qr_token
        self.qr_base64 = qr_base64
        self.status = "PENDING"
        self.message = "请用微信扫一扫登录"
        self.expires_at = time.time() + expires_in

    def to_dict(self) -> dict[str, Any]:
        return {
            "status": self.status,
            "qrBase64": self.qr_base64,
            "qrToken": self.qr_token,
            "message": self.message,
            "expiresAt": _iso(self.expires_at),
        }


class QrLoginManager:
    """持有唯一进行中的扫码会话，负责创建、推进、超时与成功后落库。"""

    def __init__(self, client: WereadClientLike) -> None:
        self._client = client
        self._session: QrLoginSession | None = None

    @property
    def active(self) -> bool:
        return self._session is not None

    async def create(self) -> dict[str, Any]:
        data = await self._client.qr_create()
        self._session = QrLoginSession(
            qr_token=str(data.get("qrToken") or ""),
            qr_base64=render_qr_png(str(data.get("qrText") or "")),
            expires_in=int(data.get("expiresIn") or 180),
        )
        return self._session.to_dict()

    async def poll(self) -> dict[str, Any] | None:
        """推进一次状态。没有进行中的会话返回 None；成功/过期/失败后清空会话。"""
        session = self._session
        if session is None:
            return None
        if time.time() >= session.expires_at:
            session.status = "EXPIRED"
            session.message = "二维码已过期，请点击重新获取"
            self._session = None
            return session.to_dict()
        try:
            result = await self._client.qr_poll(session.qr_token)
        except WereadError as exc:
            session.status = "FAILED"
            session.message = exc.message
            self._session = None
            return session.to_dict()
        status = str(result.get("status") or "").upper()
        if status == "SCANNED":
            session.status = "SCANNED"
            session.message = "已扫码，请在手机上确认"
            return session.to_dict()
        if status == "SUCCESS":
            user = result.get("user") if isinstance(result.get("user"), dict) else None
            cookie = result.get("cookie") if isinstance(result.get("cookie"), dict) else {}
            await self._client.persist_qr_login(user, cookie)
            session.status = "SUCCESS"
            session.message = "登录成功"
            self._session = None
            return session.to_dict()
        if status == "FAILED":
            session.status = "FAILED"
            session.message = "登录失败，请重试"
            self._session = None
            return session.to_dict()
        return session.to_dict()

    def clear(self) -> None:
        self._session = None


def _iso(timestamp: float) -> str:
    from datetime import datetime

    return datetime.fromtimestamp(timestamp, tz=UTC).isoformat(timespec="seconds").replace("+00:00", "Z")
