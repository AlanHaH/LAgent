"""图书端点（仅供 Java 后端经 X-Internal-Token 调用）。"""

from __future__ import annotations

from typing import Literal

from fastapi import APIRouter, Depends, Query, Request

from app.books.schemas import SetApiKeyRequest
from app.core.responses import success
from app.core.security import require_internal_token

router = APIRouter(
    prefix="/internal/v1/books",
    tags=["books"],
    dependencies=[Depends(require_internal_token)],
)


@router.get("/login-status")
async def login_status(request: Request) -> dict[str, object]:
    view = await request.app.state.weread_books_service.login_status()
    return success(request, view.model_dump(by_alias=True, mode="json"))


@router.post("/login-qrcode")
async def login_qrcode(request: Request) -> dict[str, object]:
    view = await request.app.state.weread_books_service.start_qr_login()
    return success(request, view.model_dump(by_alias=True, mode="json"))


@router.post("/api-key")
async def set_api_key(body: SetApiKeyRequest, request: Request) -> dict[str, object]:
    view = await request.app.state.weread_books_service.set_api_key(body.api_key)
    return success(request, view.model_dump(by_alias=True, mode="json"))


@router.post("/logout")
async def logout(request: Request) -> dict[str, object]:
    await request.app.state.weread_books_service.logout()
    return success(request, {"logout": True})


@router.get("/bookshelf")
async def bookshelf(request: Request) -> dict[str, object]:
    view = await request.app.state.weread_books_service.bookshelf()
    return success(request, view.model_dump(by_alias=True, mode="json"))


@router.get("/search")
async def search(
    request: Request,
    keyword: str = Query(min_length=1, max_length=100),
    count: int = Query(default=10, ge=1, le=50),
) -> dict[str, object]:
    books = await request.app.state.weread_books_service.search(keyword, count)
    return success(request, {"books": [book.model_dump(by_alias=True, mode="json") for book in books]})


@router.get("/info")
async def book_info(
    request: Request,
    book_id: str = Query(alias="bookId", min_length=1, max_length=64),
) -> dict[str, object]:
    view = await request.app.state.weread_books_service.book_info(book_id)
    return success(request, view.model_dump(by_alias=True, mode="json"))


@router.get("/getprogress")
async def book_progress(
    request: Request,
    book_id: str = Query(alias="bookId", min_length=1, max_length=64),
) -> dict[str, object]:
    view = await request.app.state.weread_books_service.book_progress(book_id)
    return success(request, view.model_dump(by_alias=True, mode="json"))


ReadDataMode = Literal["weekly", "monthly", "annually", "overall"]


@router.get("/readdata-detail")
async def readdata_detail(
    request: Request,
    mode: ReadDataMode = "overall",
) -> dict[str, object]:
    view = await request.app.state.weread_books_service.readdata_detail(mode)
    return success(request, view.model_dump(by_alias=True, mode="json"))


@router.get("/recommend")
async def recommend(
    request: Request,
    count: int = Query(default=12, ge=1, le=50),
) -> dict[str, object]:
    books = await request.app.state.weread_books_service.recommend(count)
    return success(request, {"books": [book.model_dump(by_alias=True, mode="json") for book in books]})


@router.get("/similar")
async def similar(
    request: Request,
    book_id: str = Query(alias="bookId", min_length=1, max_length=64),
    count: int = Query(default=12, ge=1, le=50),
) -> dict[str, object]:
    books = await request.app.state.weread_books_service.similar(book_id, count)
    return success(request, {"books": [book.model_dump(by_alias=True, mode="json") for book in books]})
