from __future__ import annotations

import asyncio
from collections.abc import Callable
from typing import Any

from app.config import Settings
from app.model.client import LangChainChatModelClient
from app.model.schemas import (
    DeltaCallback,
    ModelClient,
    ModelCompletion,
    RuntimeModelConfiguration,
)

ClientFactory = Callable[[Settings], ModelClient]


class RuntimeModelManager:
    """Hot-swappable model client used by every AI feature service."""

    def __init__(
        self,
        settings: Settings,
        *,
        initial_client: ModelClient | None = None,
        client_factory: ClientFactory | None = None,
    ) -> None:
        self._base_settings = settings
        self._factory = client_factory or LangChainChatModelClient
        self._client = initial_client or self._factory(settings)
        self._source = "injected" if initial_client is not None else "environment"
        self._lock = asyncio.Lock()

    @property
    def configured(self) -> bool:
        return self._client.configured

    @property
    def model_name(self) -> str:
        return self._client.model_name

    @property
    def source(self) -> str:
        return self._source

    async def close(self) -> None:
        await self._client.close()

    async def complete(
        self,
        system_prompt: str,
        user_prompt: str,
        *,
        max_output_tokens: int | None = None,
    ) -> ModelCompletion:
        client = self._client
        return await client.complete(
            system_prompt,
            user_prompt,
            max_output_tokens=max_output_tokens,
        )

    async def complete_streaming(
        self,
        system_prompt: str,
        user_prompt: str,
        on_delta: DeltaCallback,
        *,
        max_output_tokens: int | None = None,
    ) -> ModelCompletion:
        client = self._client
        return await client.complete_streaming(
            system_prompt,
            user_prompt,
            on_delta,
            max_output_tokens=max_output_tokens,
        )

    async def test(self, configuration: RuntimeModelConfiguration) -> dict[str, Any]:
        candidate = self._factory(self._settings(configuration))
        try:
            result = await candidate.complete(
                "You are a connectivity probe. Follow the user's instruction exactly.",
                "Reply with exactly: OK",
                max_output_tokens=8,
            )
            return {
                "ready": True,
                "model": result.model or configuration.model_name,
                "latencyMs": result.latency_ms,
            }
        finally:
            await candidate.close()

    async def configure(self, configuration: RuntimeModelConfiguration) -> dict[str, Any]:
        candidate = self._factory(self._settings(configuration))
        try:
            result = await candidate.complete(
                "You are a connectivity probe. Follow the user's instruction exactly.",
                "Reply with exactly: OK",
                max_output_tokens=8,
            )
        except Exception:
            await candidate.close()
            raise

        async with self._lock:
            previous = self._client
            self._client = candidate
            self._source = "runtime"
        await previous.close()
        return {
            "ready": True,
            "model": candidate.model_name,
            "latencyMs": result.latency_ms,
            "source": self._source,
        }

    def status(self) -> dict[str, Any]:
        return {
            "ready": self.configured,
            "model": self.model_name or None,
            "source": self._source,
        }

    def _settings(self, configuration: RuntimeModelConfiguration) -> Settings:
        return self._base_settings.model_copy(
            update={
                "model_enabled": True,
                "model_base_url": configuration.base_url.strip(),
                "model_api_key": configuration.api_key,
                "model_name": configuration.model_name.strip(),
                "model_timeout_seconds": configuration.timeout_seconds,
                "model_connect_timeout_seconds": min(
                    configuration.timeout_seconds, 60
                ),
                "model_max_output_tokens": configuration.max_output_tokens,
                "model_thinking": configuration.thinking,
                "model_allow_http": configuration.allow_http,
            }
        )
