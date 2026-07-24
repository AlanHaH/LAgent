from __future__ import annotations

import asyncio
import json
import time
from collections.abc import Mapping
from typing import Any
from urllib.parse import urlparse

import httpx

from app.config import Settings
from app.core.errors import ServiceError
from app.model.schemas import DeltaCallback, ModelCompletion


class OpenAICompatibleClient:
    def __init__(self, settings: Settings, transport: httpx.AsyncBaseTransport | None = None) -> None:
        self._settings = settings
        self._endpoint = self._build_endpoint(settings.model_base_url) if settings.model_base_url else ""
        self._semaphore = asyncio.Semaphore(settings.model_max_concurrency)
        timeout = httpx.Timeout(
            settings.model_timeout_seconds,
            connect=settings.model_connect_timeout_seconds,
        )
        headers = {"Authorization": f"Bearer {settings.model_api_key.get_secret_value()}"}
        self._client = httpx.AsyncClient(timeout=timeout, headers=headers, transport=transport)

    @property
    def configured(self) -> bool:
        return self._settings.model_configured

    @property
    def model_name(self) -> str:
        return self._settings.model_name

    async def close(self) -> None:
        await self._client.aclose()

    async def complete(
        self,
        system_prompt: str,
        user_prompt: str,
        *,
        max_output_tokens: int | None = None,
    ) -> ModelCompletion:
        self._require_configured()
        started = time.perf_counter()
        async with self._semaphore:
            try:
                response = await self._client.post(
                    self._endpoint,
                    json=self._request(system_prompt, user_prompt, False, max_output_tokens),
                )
                self._raise_for_status(response)
                payload = response.json()
            except ServiceError:
                raise
            except httpx.TimeoutException as error:
                raise ServiceError(
                    "AI_MODEL_TIMEOUT", "模型响应超时", status_code=504, retryable=True
                ) from error
            except httpx.HTTPError as error:
                raise ServiceError(
                    "AI_PROVIDER_ERROR", "模型服务连接失败", status_code=502, retryable=True
                ) from error
            except (ValueError, json.JSONDecodeError) as error:
                raise ServiceError("AI_PROVIDER_ERROR", "模型返回无效 JSON", status_code=502) from error

        content = self._content(payload)
        usage = payload.get("usage") if isinstance(payload, Mapping) else {}
        return ModelCompletion(
            content=content,
            model=self.model_name,
            prompt_tokens=self._integer(usage, "prompt_tokens"),
            completion_tokens=self._integer(usage, "completion_tokens"),
            latency_ms=round((time.perf_counter() - started) * 1000),
        )

    async def complete_streaming(
        self,
        system_prompt: str,
        user_prompt: str,
        on_delta: DeltaCallback,
        *,
        max_output_tokens: int | None = None,
    ) -> ModelCompletion:
        self._require_configured()
        started = time.perf_counter()
        first_token_ms: int | None = None
        pieces: list[str] = []
        prompt_tokens: int | None = None
        completion_tokens: int | None = None
        async with self._semaphore:
            try:
                async with self._client.stream(
                    "POST",
                    self._endpoint,
                    json=self._request(system_prompt, user_prompt, True, max_output_tokens),
                    headers={"Accept": "text/event-stream"},
                ) as response:
                    self._raise_for_status(response)
                    async for line in response.aiter_lines():
                        if not line.startswith("data:"):
                            continue
                        raw = line[5:].strip()
                        if not raw or raw == "[DONE]":
                            continue
                        try:
                            event = json.loads(raw)
                        except json.JSONDecodeError as error:
                            raise ServiceError(
                                "AI_PROVIDER_ERROR", "模型流包含无效 JSON", status_code=502
                            ) from error
                        usage = event.get("usage") if isinstance(event, Mapping) else None
                        if isinstance(usage, Mapping):
                            prompt_tokens = self._integer(usage, "prompt_tokens")
                            completion_tokens = self._integer(usage, "completion_tokens")
                        piece = self._delta(event)
                        if not piece:
                            continue
                        if first_token_ms is None:
                            first_token_ms = round((time.perf_counter() - started) * 1000)
                        if sum(map(len, pieces)) + len(piece) > 10_000:
                            raise ServiceError("AI_OUTPUT_INVALID", "模型输出超过长度限制", status_code=422)
                        pieces.append(piece)
                        await on_delta(piece)
            except ServiceError:
                raise
            except httpx.TimeoutException as error:
                raise ServiceError(
                    "AI_MODEL_TIMEOUT", "模型流响应超时", status_code=504, retryable=True
                ) from error
            except httpx.HTTPError as error:
                raise ServiceError(
                    "AI_PROVIDER_ERROR", "模型流连接失败", status_code=502, retryable=True
                ) from error

        content = "".join(pieces).strip()
        if not content:
            raise ServiceError("AI_PROVIDER_ERROR", "模型没有返回内容", status_code=502)
        return ModelCompletion(
            content=content,
            model=self.model_name,
            prompt_tokens=prompt_tokens,
            completion_tokens=completion_tokens,
            latency_ms=round((time.perf_counter() - started) * 1000),
            first_token_latency_ms=first_token_ms,
        )

    def _request(
        self,
        system_prompt: str,
        user_prompt: str,
        stream: bool,
        max_output_tokens: int | None,
    ) -> dict[str, Any]:
        request: dict[str, Any] = {
            "model": self.model_name,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            "stream": stream,
            "max_tokens": max_output_tokens or self._settings.model_max_output_tokens,
        }
        if self._settings.model_thinking:
            request["thinking"] = {"type": self._settings.model_thinking}
        if stream:
            request["stream_options"] = {"include_usage": True}
        return request

    def _build_endpoint(self, raw: str) -> str:
        value = raw.strip().rstrip("/")
        parsed = urlparse(value)
        allowed_scheme = parsed.scheme == "https" or (
            self._settings.model_allow_http and parsed.scheme == "http"
        )
        if not allowed_scheme or not parsed.hostname or parsed.username or parsed.query or parsed.fragment:
            raise ValueError("AI_MODEL_BASE_URL must be a safe HTTP(S) origin")
        return value if value.endswith("/chat/completions") else f"{value}/chat/completions"

    def _require_configured(self) -> None:
        if not self.configured:
            raise ServiceError(
                "AI_DEPENDENCY_UNAVAILABLE",
                "模型服务尚未配置",
                status_code=503,
                retryable=False,
            )

    def _raise_for_status(self, response: httpx.Response) -> None:
        if response.is_success:
            return
        if response.status_code == 429:
            raise ServiceError("AI_RATE_LIMITED", "模型调用达到限额", status_code=429, retryable=True)
        raise ServiceError(
            "AI_PROVIDER_ERROR",
            f"模型供应商返回 HTTP {response.status_code}",
            status_code=502,
            retryable=response.status_code >= 500,
        )

    def _content(self, payload: Any) -> str:
        try:
            raw = payload["choices"][0]["message"]["content"]
        except (KeyError, IndexError, TypeError, AttributeError) as error:
            raise ServiceError("AI_PROVIDER_ERROR", "模型响应缺少正文", status_code=502) from error
        if not isinstance(raw, str):
            raise ServiceError("AI_PROVIDER_ERROR", "模型响应缺少正文", status_code=502)
        value = raw.strip()
        if not value or len(value) > 10_000:
            raise ServiceError("AI_OUTPUT_INVALID", "模型正文无效", status_code=422)
        return value

    def _delta(self, payload: Any) -> str:
        try:
            value = payload["choices"][0]["delta"].get("content")
        except (KeyError, IndexError, TypeError, AttributeError):
            return ""
        return value if isinstance(value, str) else ""

    @staticmethod
    def _integer(payload: Any, key: str) -> int | None:
        if not isinstance(payload, Mapping):
            return None
        value = payload.get(key)
        return value if isinstance(value, int) and not isinstance(value, bool) else None
