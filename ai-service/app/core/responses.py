from __future__ import annotations

from typing import Any

from fastapi import Request

from app.core.errors import request_id


def success(request: Request, data: Any) -> dict[str, Any]:
    return {"success": True, "data": data, "requestId": request_id(request)}

