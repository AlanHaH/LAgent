from __future__ import annotations

import json

import httpx
import pytest

from app.config import Settings
from app.core.errors import ServiceError
from app.model.client import OpenAICompatibleClient


def settings() -> Settings:
    return Settings(
        env="test",
        internal_token="x" * 32,
        model_base_url="https://model.example/v1",
        model_api_key="secret",
        model_name="fake-model",
    )


@pytest.mark.asyncio
async def test_sync_completion_parses_openai_contract() -> None:
    async def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        assert str(request.url) == "https://model.example/v1/chat/completions"
        assert body["model"] == "fake-model"
        assert body["stream"] is False
        assert request.headers["authorization"] == "Bearer secret"
        return httpx.Response(
            200,
            json={
                "choices": [{"message": {"content": "模型回答"}}],
                "usage": {"prompt_tokens": 8, "completion_tokens": 5},
            },
        )

    client = OpenAICompatibleClient(settings(), httpx.MockTransport(handler))
    try:
        result = await client.complete("system", "user")
    finally:
        await client.close()

    assert result.content == "模型回答"
    assert result.prompt_tokens == 8
    assert result.completion_tokens == 5


@pytest.mark.asyncio
async def test_stream_completion_relays_deltas_and_done_marker() -> None:
    async def handler(_: httpx.Request) -> httpx.Response:
        stream = "\n".join(
            [
                'data: {"choices":[{"delta":{"content":"你"}}]}',
                "",
                'data: {"choices":[{"delta":{"content":"好"}}]}',
                "",
                "data: [DONE]",
                "",
            ]
        )
        return httpx.Response(200, content=stream.encode(), headers={"Content-Type": "text/event-stream"})

    client = OpenAICompatibleClient(settings(), httpx.MockTransport(handler))
    deltas: list[str] = []
    try:
        result = await client.complete_streaming("system", "user", _collector(deltas))
    finally:
        await client.close()

    assert deltas == ["你", "好"]
    assert result.content == "你好"
    assert result.first_token_latency_ms is not None


@pytest.mark.asyncio
async def test_provider_rate_limit_has_stable_error_code() -> None:
    async def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(429, json={"error": "limited"})

    client = OpenAICompatibleClient(settings(), httpx.MockTransport(handler))
    try:
        with pytest.raises(ServiceError) as captured:
            await client.complete("system", "user")
    finally:
        await client.close()

    assert captured.value.code == "AI_RATE_LIMITED"
    assert captured.value.retryable is True


def _collector(target: list[str]):  # type: ignore[no-untyped-def]
    async def collect(piece: str) -> None:
        target.append(piece)

    return collect
