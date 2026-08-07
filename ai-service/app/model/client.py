from __future__ import annotations

import asyncio
import logging
import re
import time
from typing import Any
from urllib.parse import urlparse

from langchain_core.messages import HumanMessage, SystemMessage

from app.config import Settings
from app.core.errors import ServiceError
from app.model.schemas import DeltaCallback, ModelCompletion

logger = logging.getLogger(__name__)


class LangChainChatModelClient:
    """基于 LangChain ChatOpenAI 的模型客户端，实现 ``ModelClient`` Protocol。

    用 LangChain 的 ``ChatOpenAI`` 替代手写 httpx 请求与 SSE 流解析，
    同时保留并发控制（Semaphore）、URL 安全校验、错误码映射与输出长度限制，
    对外接口与原 ``OpenAICompatibleClient`` 完全一致。
    """

    def __init__(
        self,
        settings: Settings,
        *,
        llm: Any | None = None,
    ) -> None:
        self._settings = settings
        self._semaphore = asyncio.Semaphore(settings.model_max_concurrency)
        self._llm = llm if llm is not None else self._build_llm(settings)
        self._model_name = settings.model_name

    # ------------------------------------------------------------------ #
    #  ModelClient Protocol
    # ------------------------------------------------------------------ #

    @property
    def configured(self) -> bool:
        return self._settings.model_configured

    @property
    def model_name(self) -> str:
        return self._model_name

    async def close(self) -> None:
        # LangChain ChatOpenAI 不需要显式关闭底层 httpx 客户端
        return None

    async def complete(
        self,
        system_prompt: str,
        user_prompt: str,
        *,
        max_output_tokens: int | None = None,
    ) -> ModelCompletion:
        self._require_configured()
        started = time.perf_counter()
        messages = self._messages(system_prompt, user_prompt)
        bound = self._bind_max_tokens(max_output_tokens)
        async with self._semaphore:
            try:
                response = await bound.ainvoke(messages)
            except ServiceError:
                raise
            except Exception as error:
                raise self._map_error(error) from error

        content = self._extract_content(response)
        prompt_tokens, completion_tokens = self._extract_usage(response)
        return ModelCompletion(
            content=content,
            model=self.model_name,
            prompt_tokens=prompt_tokens,
            completion_tokens=completion_tokens,
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
        messages = self._messages(system_prompt, user_prompt)
        bound = self._bind_max_tokens(max_output_tokens)

        async with self._semaphore:
            try:
                async for chunk in bound.astream(messages):
                    prompt_tokens, completion_tokens = self._merge_chunk_usage(
                        chunk, prompt_tokens, completion_tokens
                    )
                    text = chunk.content if isinstance(chunk.content, str) else ""
                    if not text:
                        continue
                    if first_token_ms is None:
                        first_token_ms = round((time.perf_counter() - started) * 1000)
                    if sum(map(len, pieces)) + len(text) > 10_000:
                        raise ServiceError(
                            "AI_OUTPUT_INVALID", "模型输出超过长度限制", status_code=422
                        )
                    pieces.append(text)
                    await on_delta(text)
            except ServiceError:
                raise
            except Exception as error:
                raise self._map_error(error) from error

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

    # ------------------------------------------------------------------ #
    #  内部方法
    # ------------------------------------------------------------------ #

    @staticmethod
    def _messages(system_prompt: str, user_prompt: str) -> list[Any]:
        if SystemMessage is None:  # pragma: no cover
            raise ServiceError("AI_DEPENDENCY_UNAVAILABLE", "langchain-core 未安装")
        return [
            SystemMessage(content=system_prompt),
            HumanMessage(content=user_prompt),
        ]

    def _bind_max_tokens(self, max_output_tokens: int | None) -> Any:
        """按调用覆盖 max_tokens；不支持 bind 的模型直接透传。"""
        limit = max_output_tokens or self._settings.model_max_output_tokens
        bind = getattr(self._llm, "bind", None)
        if bind is None:
            return self._llm
        try:
            return bind(max_tokens=limit)
        except Exception:
            return self._llm

    @staticmethod
    def _build_llm(settings: Settings) -> Any:
        from langchain_openai import ChatOpenAI

        LangChainChatModelClient._validate_base_url(settings)
        kwargs: dict[str, Any] = {
            "model": settings.model_name,
            "max_tokens": settings.model_max_output_tokens,
            "timeout": settings.model_timeout_seconds,
            "max_retries": 0,
        }
        if settings.model_base_url:
            kwargs["base_url"] = settings.model_base_url
        key = settings.model_api_key.get_secret_value()
        if key:
            kwargs["api_key"] = key
        # 显式下发 thinking 参数：部分模型（如 deepseek-v4-flash）默认开启推理，
        # 推理会大量消耗输出预算并显著拉长延迟，disabled 时也必须显式关闭
        if settings.model_thinking in ("enabled", "disabled"):
            kwargs["model_kwargs"] = {"extra_body": {"thinking": {"type": settings.model_thinking}}}
        try:
            return ChatOpenAI(**kwargs)
        except TypeError:
            # 旧版 langchain-openai 使用 openai_api_base / openai_api_key 参数名
            kwargs.pop("base_url", None)
            kwargs.pop("api_key", None)
            if settings.model_base_url:
                kwargs["openai_api_base"] = settings.model_base_url
            if key:
                kwargs["openai_api_key"] = key
            return ChatOpenAI(**kwargs)

    @staticmethod
    def _validate_base_url(settings: Settings) -> None:
        if not settings.model_base_url:
            return
        parsed = urlparse(settings.model_base_url.strip().rstrip("/"))
        allowed_scheme = parsed.scheme == "https" or (
            settings.model_allow_http and parsed.scheme == "http"
        )
        if (
            not allowed_scheme
            or not parsed.hostname
            or parsed.username
            or parsed.query
            or parsed.fragment
        ):
            raise ValueError("AI_MODEL_BASE_URL must be a safe HTTP(S) origin")

    def _require_configured(self) -> None:
        if not self.configured:
            raise ServiceError(
                "AI_DEPENDENCY_UNAVAILABLE",
                "模型服务尚未配置",
                status_code=503,
                retryable=False,
            )

    @staticmethod
    def _extract_content(response: Any) -> str:
        raw = getattr(response, "content", None)
        if not isinstance(raw, str):
            raise ServiceError("AI_PROVIDER_ERROR", "模型响应缺少正文", status_code=502)
        value = raw.strip()
        if not value or len(value) > 10_000:
            raise ServiceError("AI_OUTPUT_INVALID", "模型正文无效", status_code=422)
        return value

    @staticmethod
    def _extract_usage(response: Any) -> tuple[int | None, int | None]:
        usage = getattr(response, "usage_metadata", None)
        if isinstance(usage, dict):
            return usage.get("input_tokens"), usage.get("output_tokens")
        metadata = getattr(response, "response_metadata", None)
        if isinstance(metadata, dict):
            token_usage = metadata.get("token_usage") or {}
            if isinstance(token_usage, dict):
                return token_usage.get("prompt_tokens"), token_usage.get("completion_tokens")
        return None, None

    @staticmethod
    def _merge_chunk_usage(
        chunk: Any, prompt_tokens: int | None, completion_tokens: int | None
    ) -> tuple[int | None, int | None]:
        usage = getattr(chunk, "usage_metadata", None)
        if isinstance(usage, dict):
            return usage.get("input_tokens") or prompt_tokens, usage.get(
                "output_tokens"
            ) or completion_tokens
        metadata = getattr(chunk, "response_metadata", None)
        if isinstance(metadata, dict):
            token_usage = metadata.get("token_usage") or {}
            if isinstance(token_usage, dict):
                return (
                    token_usage.get("prompt_tokens") or prompt_tokens,
                    token_usage.get("completion_tokens") or completion_tokens,
                )
        return prompt_tokens, completion_tokens

    @staticmethod
    def _map_error(error: Exception) -> ServiceError:
        if isinstance(error, ServiceError):
            return error
        name = type(error).__name__
        message = str(error)
        details = LangChainChatModelClient._provider_error_details(error)
        status = details.get("providerStatus")
        provider_code = str(details.get("providerCode", "")).lower()
        logger.warning("Model provider request failed: exception=%s details=%s", name, details)
        if status == 429 or "RateLimit" in name or "429" in message:
            return ServiceError(
                "AI_RATE_LIMITED",
                "模型调用达到限额",
                status_code=429,
                retryable=True,
                details=details,
            )
        if status == 402 or provider_code in {"insufficient_balance", "insufficient_quota"}:
            return ServiceError(
                "AI_QUOTA_EXCEEDED",
                "模型账户余额或调用额度不足",
                status_code=429,
                retryable=False,
                details=details,
            )
        if status in {401, 403}:
            return ServiceError(
                "AI_PROVIDER_AUTH_FAILED",
                "模型 API 密钥无效或无权访问当前模型",
                status_code=503,
                retryable=False,
                details=details,
            )
        if status == 404:
            return ServiceError(
                "AI_MODEL_NOT_FOUND",
                "模型不存在，或 Base URL 不应包含 /chat/completions",
                status_code=502,
                retryable=False,
                details=details,
            )
        if "Timeout" in name or "timed out" in message.lower():
            return ServiceError(
                "AI_MODEL_TIMEOUT",
                "模型响应超时",
                status_code=504,
                retryable=True,
                details=details,
            )
        return ServiceError(
            "AI_PROVIDER_ERROR",
            "模型服务调用失败",
            status_code=502,
            retryable=status is None or status >= 500,
            details=details,
        )

    @staticmethod
    def _provider_error_details(error: Exception) -> dict[str, Any]:
        """Return non-sensitive provider metadata suitable for internal diagnostics."""
        details: dict[str, Any] = {"providerException": type(error).__name__}
        status = getattr(error, "status_code", None)
        if isinstance(status, int):
            details["providerStatus"] = status

        code = getattr(error, "code", None)
        if isinstance(code, str) and code:
            details["providerCode"] = code[:80]

        error_type = getattr(error, "type", None)
        if isinstance(error_type, str) and error_type:
            details["providerType"] = error_type[:80]

        body = getattr(error, "body", None)
        if isinstance(body, dict):
            nested = body.get("error") if isinstance(body.get("error"), dict) else body
            if isinstance(nested, dict):
                body_code = nested.get("code")
                body_type = nested.get("type")
                if isinstance(body_code, str) and body_code:
                    details.setdefault("providerCode", body_code[:80])
                if isinstance(body_type, str) and body_type:
                    details.setdefault("providerType", body_type[:80])

        cause = error.__cause__ or error.__context__
        if isinstance(cause, BaseException):
            details["providerCause"] = type(cause).__name__
            deepest = cause
            for _ in range(3):
                nested = deepest.__cause__ or deepest.__context__
                if not isinstance(nested, BaseException):
                    break
                deepest = nested
            reason = str(deepest).strip()
            if reason:
                reason = re.sub(r"sk-[A-Za-z0-9_-]+", "sk-***", reason)
                reason = re.sub(r"(https?://[^\\s?]+)\\?[^\\s]+", r"\\1?***", reason)
                details["providerReason"] = reason[:240]
            winerror = getattr(deepest, "winerror", None)
            errno = getattr(deepest, "errno", None)
            if isinstance(winerror, int):
                details["providerWinError"] = winerror
            elif isinstance(errno, int):
                details["providerErrno"] = errno
        return details


# 兼容旧导入名
OpenAICompatibleClient = LangChainChatModelClient
