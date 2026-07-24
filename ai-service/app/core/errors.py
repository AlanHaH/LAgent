from __future__ import annotations

from typing import Any

from fastapi import Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse


class ServiceError(Exception):
    def __init__(
        self,
        code: str,
        message: str,
        *,
        status_code: int = 500,
        retryable: bool = False,
        details: dict[str, Any] | None = None,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status_code = status_code
        self.retryable = retryable
        self.details = details or {}


def request_id(request: Request) -> str:
    return str(getattr(request.state, "request_id", "unknown"))


async def service_error_handler(request: Request, error: ServiceError) -> JSONResponse:
    return JSONResponse(
        status_code=error.status_code,
        content={
            "success": False,
            "error": {
                "code": error.code,
                "message": error.message,
                "retryable": error.retryable,
                "details": error.details,
            },
            "requestId": request_id(request),
        },
    )


async def unexpected_error_handler(request: Request, _: Exception) -> JSONResponse:
    return JSONResponse(
        status_code=500,
        content={
            "success": False,
            "error": {
                "code": "AI_INTERNAL_ERROR",
                "message": "AI 服务内部错误",
                "retryable": False,
                "details": {},
            },
            "requestId": request_id(request),
        },
    )


async def validation_error_handler(request: Request, error: RequestValidationError) -> JSONResponse:
    fields = [".".join(str(part) for part in item.get("loc", ())) for item in error.errors()[:20]]
    return JSONResponse(
        status_code=422,
        content={
            "success": False,
            "error": {
                "code": "AI_REQUEST_INVALID",
                "message": "请求参数校验失败",
                "retryable": False,
                "details": {"fields": fields},
            },
            "requestId": request_id(request),
        },
    )
