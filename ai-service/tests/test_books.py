"""图书域离线测试：注入 FakeWereadMcpClient，不依赖 mcp 包与 weread-mcp 服务。"""

from __future__ import annotations

from contextlib import asynccontextmanager

import httpx
import pytest

from app.books.fake import FakeWereadMcpClient
from app.books.mcp_client import WereadMcpClient
from app.config import Settings
from app.core.errors import ServiceError
from app.main import create_app
from app.rag.embeddings import HashEmbeddingProvider
from app.rag.vector_store import QdrantVectorStore
from tests.fakes import FakeModelClient

TOKEN = "test-internal-token-which-is-long-enough"


def make_settings() -> Settings:
    return Settings(
        env="test",
        internal_token=TOKEN,
        model_base_url="https://example.test/v1",
        model_api_key="test-key",
        model_name="fake-model",
        embedding_provider="hash",
        qdrant_mode="memory",
    )


@asynccontextmanager
async def client_context(fake: FakeWereadMcpClient):  # type: ignore[no-untyped-def]
    configured = make_settings()
    app = create_app(
        configured,
        model_client=FakeModelClient(),
        embeddings=HashEmbeddingProvider(),
        vector_store=QdrantVectorStore(configured),
        weread_gateway=fake,
    )
    async with app.router.lifespan_context(app):
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            yield client


def headers() -> dict[str, str]:
    return {"X-Internal-Token": TOKEN, "X-Request-Id": "req-books"}


@pytest.mark.asyncio
async def test_login_status_unlogged() -> None:
    async with client_context(FakeWereadMcpClient()) as client:
        resp = await client.get("/internal/v1/books/login-status", headers=headers())
        assert resp.status_code == 200
        data = resp.json()["data"]
        assert data["loggedIn"] is False
        assert data["loginQr"] is None


@pytest.mark.asyncio
async def test_qr_login_flow_polls_to_success() -> None:
    async with client_context(FakeWereadMcpClient()) as client:
        qr = await client.post("/internal/v1/books/login-qrcode", headers=headers())
        assert qr.status_code == 200
        qr_data = qr.json()["data"]
        assert qr_data["status"] == "PENDING"
        assert qr_data["qrBase64"].startswith("data:image/png;base64,")

        data = None
        for _ in range(5):
            resp = await client.get("/internal/v1/books/login-status", headers=headers())
            data = resp.json()["data"]
            if data["loggedIn"]:
                break
        assert data is not None
        assert data["loggedIn"] is True
        assert data["loginType"] == "QR_CODE"


@pytest.mark.asyncio
async def test_api_key_login_then_bookshelf() -> None:
    async with client_context(FakeWereadMcpClient()) as client:
        resp = await client.post(
            "/internal/v1/books/api-key", headers=headers(), json={"apiKey": "wrk-fake-key"}
        )
        assert resp.status_code == 200
        assert resp.json()["data"]["loggedIn"] is True

        shelf = await client.get("/internal/v1/books/bookshelf", headers=headers())
        data = shelf.json()["data"]
        assert data["total"] == 4
        assert data["books"][0]["bookId"]
        assert data["books"][0]["readingProgress"] is not None


@pytest.mark.asyncio
async def test_logout_clears_login() -> None:
    async with client_context(FakeWereadMcpClient()) as client:
        await client.post("/internal/v1/books/api-key", headers=headers(), json={"apiKey": "wrk-fake-key"})
        resp = await client.post("/internal/v1/books/logout", headers=headers())
        assert resp.status_code == 200
        status = await client.get("/internal/v1/books/login-status", headers=headers())
        assert status.json()["data"]["loggedIn"] is False


@pytest.mark.asyncio
async def test_bookshelf_requires_login() -> None:
    async with client_context(FakeWereadMcpClient()) as client:
        resp = await client.get("/internal/v1/books/bookshelf", headers=headers())
        assert resp.status_code == 401
        assert resp.json()["error"]["code"] == "WEREAD_NOT_LOGGED_IN"


@pytest.mark.asyncio
async def test_api_key_invalid() -> None:
    async with client_context(FakeWereadMcpClient()) as client:
        resp = await client.post("/internal/v1/books/api-key", headers=headers(), json={"apiKey": "bad-key"})
        assert resp.status_code == 401
        assert resp.json()["error"]["code"] == "WEREAD_API_KEY_INVALID"


@pytest.mark.asyncio
async def test_search() -> None:
    async with client_context(FakeWereadMcpClient()) as client:
        await client.post("/internal/v1/books/api-key", headers=headers(), json={"apiKey": "wrk-fake-key"})
        resp = await client.get("/internal/v1/books/search", headers=headers(), params={"keyword": "Java"})
        assert resp.status_code == 200
        books = resp.json()["data"]["books"]
        assert books
        assert all("java" in b["title"].lower() for b in books)


@pytest.mark.asyncio
async def test_extended_endpoints() -> None:
    async with client_context(FakeWereadMcpClient()) as client:
        await client.post("/internal/v1/books/api-key", headers=headers(), json={"apiKey": "wrk-fake-key"})

        info = await client.get("/internal/v1/books/info", headers=headers(), params={"bookId": "fake-001"})
        assert info.status_code == 200
        info_data = info.json()["data"]
        assert info_data["bookId"] == "fake-001"
        assert info_data["newRating"] == 9.5
        assert info_data["ratingDetail"]["good"] == 90

        prog = await client.get(
            "/internal/v1/books/getprogress", headers=headers(), params={"bookId": "fake-001"}
        )
        assert prog.status_code == 200
        assert prog.json()["data"]["progressPercent"] == 36

        rec = await client.get("/internal/v1/books/recommend", headers=headers(), params={"count": 6})
        assert rec.status_code == 200
        assert rec.json()["data"]["books"]
        assert rec.json()["data"]["books"][0]["deepLink"]

        sim = await client.get(
            "/internal/v1/books/similar", headers=headers(), params={"bookId": "fake-001", "count": 6}
        )
        assert sim.status_code == 200
        assert sim.json()["data"]["books"]

        stat = await client.get(
            "/internal/v1/books/readdata-detail", headers=headers(), params={"mode": "overall"}
        )
        assert stat.status_code == 200
        stat_data = stat.json()["data"]
        assert stat_data["totalReadTime"] > 0
        assert stat_data["medals"]
        assert stat_data["preferBooks"]


@pytest.mark.asyncio
async def test_extended_endpoints_require_login() -> None:
    async with client_context(FakeWereadMcpClient()) as client:
        resp = await client.get("/internal/v1/books/info", headers=headers(), params={"bookId": "fake-001"})
        assert resp.status_code == 401
        assert resp.json()["error"]["code"] == "WEREAD_NOT_LOGGED_IN"


@pytest.mark.asyncio
async def test_readdata_invalid_mode() -> None:
    async with client_context(FakeWereadMcpClient()) as client:
        await client.post("/internal/v1/books/api-key", headers=headers(), json={"apiKey": "wrk-fake-key"})
        resp = await client.get(
            "/internal/v1/books/readdata-detail", headers=headers(), params={"mode": "bad"}
        )
        assert resp.status_code == 422
        assert resp.json()["error"]["code"] == "AI_REQUEST_INVALID"


@pytest.mark.asyncio
async def test_requires_internal_token() -> None:
    async with client_context(FakeWereadMcpClient()) as client:
        resp = await client.get("/internal/v1/books/login-status")
        assert resp.status_code == 401
        assert resp.json()["error"]["code"] == "AI_INTERNAL_UNAUTHORIZED"


def test_parse_result_error_mapping() -> None:
    class Block:
        type = "text"

        def __init__(self, text: str) -> None:
            self.text = text

    class Result:
        def __init__(self, text: str) -> None:
            self.content = [Block(text)]

    parsed = WereadMcpClient._parse_result(Result('{"total": 2, "books": []}'))
    assert parsed["total"] == 2

    with pytest.raises(ServiceError) as exc:
        WereadMcpClient._parse_result(
            Result(
                '{"isError": true, "code": "WEREAD_NOT_LOGGED_IN", '
                '"message": "未登录", "status_code": 401}'
            )
        )
    assert exc.value.code == "WEREAD_NOT_LOGGED_IN"
    assert exc.value.status_code == 401
