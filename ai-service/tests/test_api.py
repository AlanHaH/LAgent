from __future__ import annotations

from contextlib import asynccontextmanager

import httpx
import pytest

from app.config import Settings
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
        rag_evidence_threshold=0.0,
    )


@asynccontextmanager
async def client_context():  # type: ignore[no-untyped-def]
    configured = make_settings()
    app = create_app(
        configured,
        model_client=FakeModelClient(),
        embeddings=HashEmbeddingProvider(),
        vector_store=QdrantVectorStore(configured),
    )
    async with app.router.lifespan_context(app):
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            yield client


@pytest.mark.asyncio
async def test_health_and_internal_auth_contract() -> None:
    async with client_context() as client:
        health = await client.get("/health/ready")
        assert health.status_code == 200
        unauthorized = await client.post(
            "/internal/v1/model/completions",
            json={"systemPrompt": "system", "userPrompt": "user"},
        )
        assert unauthorized.status_code == 401
        assert unauthorized.json()["error"]["code"] == "AI_INTERNAL_UNAUTHORIZED"


@pytest.mark.asyncio
async def test_profile_sse_contract() -> None:
    async with client_context() as client:
        response = await client.post(
            "/internal/v1/profile/interview-turns:stream",
            headers={"X-Internal-Token": TOKEN, "X-Request-Id": "req-profile"},
            json={
                "userId": 1,
                "sessionId": "session-1",
                "today": "2026-07-22",
                "currentDraft": {},
                "directionCatalog": [],
                "recentConversation": [],
                "latestUserMessage": "我想学 Java",
            },
        )
        assert response.status_code == 200
        assert response.headers["content-type"].startswith("text/event-stream")
        assert "event: message.started" in response.text
        assert "event: message.delta" in response.text
        assert "event: message.completed" in response.text
        assert '"directionQuery":"Java"' in response.text
