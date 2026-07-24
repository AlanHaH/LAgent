from __future__ import annotations

import secrets

from fastapi import Header, Request

from app.config import Settings
from app.core.errors import ServiceError


async def require_internal_token(
    request: Request,
    x_internal_token: str | None = Header(default=None, alias="X-Internal-Token"),
    x_request_id: str | None = Header(default=None, alias="X-Request-Id"),
) -> None:
    settings: Settings = request.app.state.settings
    expected = settings.internal_token.get_secret_value()
    supplied = x_internal_token or ""
    if not secrets.compare_digest(supplied.encode("utf-8"), expected.encode("utf-8")):
        raise ServiceError(
            "AI_INTERNAL_UNAUTHORIZED",
            "内部服务认证失败",
            status_code=401,
        )
    if not x_request_id or not x_request_id.strip() or len(x_request_id) > 128:
        raise ServiceError("AI_REQUEST_INVALID", "缺少有效的 X-Request-Id", status_code=400)
