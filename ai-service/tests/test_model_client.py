from __future__ import annotations

from typing import Any

import pytest

from app.config import Settings
from app.core.errors import ServiceError
from app.model.client import LangChainChatModelClient


def settings() -> Settings:
    return Settings(
        env="test",
        internal_token="x" * 32,
        model_base_url="https://model.example/v1",
        model_api_key="secret",
        model_name="fake-model",
    )


class _FakeMessage:
    """Simulates a LangChain AIMessage with content and usage metadata."""

    def __init__(self, content: str, *, usage: dict[str, int] | None = None) -> None:
        self.content = content
        self.usage_metadata = usage
        self.response_metadata: dict[str, Any] = {}


class _FakeChunk:
    def __init__(self, content: str = "", *, usage: dict[str, int] | None = None) -> None:
        self.content = content
        self.usage_metadata = usage
        self.response_metadata: dict[str, Any] = {}


class _FakeChatModel:
    """Minimal fake implementing the LangChain BaseChatModel interface used by tests."""

    def __init__(self, *, content: str = "模型回答", error: Exception | None = None) -> None:
        self._content = content
        self._error = error

    def bind(self, **kwargs: Any) -> _FakeChatModel:
        return self

    async def ainvoke(self, messages: list, **kwargs: Any) -> _FakeMessage:
        if self._error is not None:
            raise self._error
        return _FakeMessage(self._content, usage={"input_tokens": 8, "output_tokens": 5})

    async def astream(self, messages: list, **kwargs: Any):  # type: ignore[override]
        if self._error is not None:
            raise self._error
        midpoint = max(1, len(self._content) // 2)
        yield _FakeChunk(self._content[:midpoint])
        yield _FakeChunk(self._content[midpoint:])
        yield _FakeChunk(usage={"input_tokens": 8, "output_tokens": 5})


class _ProviderError(RuntimeError):
    def __init__(self, status_code: int, code: str) -> None:
        super().__init__("provider request failed")
        self.status_code = status_code
        self.code = code
        self.body = {"error": {"code": code, "type": "provider_error"}}


@pytest.mark.asyncio
async def test_sync_completion_parses_content_and_usage() -> None:
    client = LangChainChatModelClient(settings(), llm=_FakeChatModel(content="模型回答"))
    try:
        result = await client.complete("system", "user")
    finally:
        await client.close()

    assert result.content == "模型回答"
    assert result.prompt_tokens == 8
    assert result.completion_tokens == 5


@pytest.mark.asyncio
async def test_stream_completion_relays_deltas_and_content() -> None:
    client = LangChainChatModelClient(settings(), llm=_FakeChatModel(content="你好"))
    deltas: list[str] = []
    try:
        result = await client.complete_streaming("system", "user", _collector(deltas))
    finally:
        await client.close()

    assert "".join(deltas) == "你好"
    assert result.content == "你好"
    assert result.first_token_latency_ms is not None


@pytest.mark.asyncio
async def test_rate_limit_error_maps_to_stable_code() -> None:
    client = LangChainChatModelClient(
        settings(),
        llm=_FakeChatModel(error=RuntimeError("429 Too Many Requests")),
    )
    try:
        with pytest.raises(ServiceError) as captured:
            await client.complete("system", "user")
    finally:
        await client.close()

    assert captured.value.code == "AI_RATE_LIMITED"
    assert captured.value.retryable is True


@pytest.mark.asyncio
async def test_timeout_error_maps_to_timeout_code() -> None:
    client = LangChainChatModelClient(
        settings(),
        llm=_FakeChatModel(error=TimeoutError("Request timed out")),
    )
    try:
        with pytest.raises(ServiceError) as captured:
            await client.complete("system", "user")
    finally:
        await client.close()

    assert captured.value.code == "AI_MODEL_TIMEOUT"


@pytest.mark.asyncio
async def test_insufficient_balance_maps_to_quota_code_without_exposing_message() -> None:
    client = LangChainChatModelClient(
        settings(),
        llm=_FakeChatModel(error=_ProviderError(402, "insufficient_balance")),
    )
    try:
        with pytest.raises(ServiceError) as captured:
            await client.complete("system", "user")
    finally:
        await client.close()

    assert captured.value.code == "AI_QUOTA_EXCEEDED"
    assert captured.value.retryable is False
    assert captured.value.details == {
        "providerException": "_ProviderError",
        "providerStatus": 402,
        "providerCode": "insufficient_balance",
        "providerType": "provider_error",
    }


@pytest.mark.asyncio
async def test_provider_authentication_error_is_not_retryable() -> None:
    client = LangChainChatModelClient(
        settings(),
        llm=_FakeChatModel(error=_ProviderError(401, "invalid_api_key")),
    )
    try:
        with pytest.raises(ServiceError) as captured:
            await client.complete("system", "user")
    finally:
        await client.close()

    assert captured.value.code == "AI_PROVIDER_AUTH_FAILED"
    assert captured.value.retryable is False


@pytest.mark.asyncio
async def test_model_not_found_explains_base_url_misconfiguration() -> None:
    client = LangChainChatModelClient(
        settings(),
        llm=_FakeChatModel(error=_ProviderError(404, "model_not_found")),
    )
    try:
        with pytest.raises(ServiceError) as captured:
            await client.complete("system", "user")
    finally:
        await client.close()

    assert captured.value.code == "AI_MODEL_NOT_FOUND"
    assert captured.value.retryable is False
    assert "Base URL" in captured.value.message


def _collector(target: list[str]):  # type: ignore[no-untyped-def]
    async def collect(piece: str) -> None:
        target.append(piece)

    return collect
