"""图书业务服务：把 weread-mcp 工具输出校验为契约模型。"""

from __future__ import annotations

from app.books.gateway import WereadMcpGateway
from app.books.schemas import (
    BookInfoView,
    BookIntroView,
    BookLoginStatusView,
    BookProgressView,
    BookQrLoginView,
    BookShelfView,
    BookView,
    ReadDataDetailView,
)


class WereadBooksService:
    def __init__(self, gateway: WereadMcpGateway) -> None:
        self._gateway = gateway

    async def login_status(self) -> BookLoginStatusView:
        return BookLoginStatusView.model_validate(await self._gateway.login_status())

    async def start_qr_login(self) -> BookQrLoginView:
        return BookQrLoginView.model_validate(await self._gateway.login_qrcode())

    async def set_api_key(self, api_key: str) -> BookLoginStatusView:
        return BookLoginStatusView.model_validate(await self._gateway.set_api_key(api_key))

    async def logout(self) -> None:
        await self._gateway.logout()

    async def bookshelf(self) -> BookShelfView:
        return BookShelfView.model_validate(await self._gateway.get_bookshelf())

    async def search(self, keyword: str, count: int) -> list[BookView]:
        data = await self._gateway.search_books(keyword, count)
        return [BookView.model_validate(item) for item in data.get("books", [])]

    async def book_info(self, book_id: str) -> BookInfoView:
        return BookInfoView.model_validate(await self._gateway.book_info(book_id))

    async def book_progress(self, book_id: str) -> BookProgressView:
        return BookProgressView.model_validate(await self._gateway.book_progress(book_id))

    async def readdata_detail(self, mode: str) -> ReadDataDetailView:
        return ReadDataDetailView.model_validate(await self._gateway.readdata_detail(mode))

    async def recommend(self, count: int) -> list[BookIntroView]:
        data = await self._gateway.recommend_books(count)
        return [BookIntroView.model_validate(item) for item in data.get("books", [])]

    async def similar(self, book_id: str, count: int) -> list[BookIntroView]:
        data = await self._gateway.similar_books(book_id, count)
        return [BookIntroView.model_validate(item) for item in data.get("books", [])]
