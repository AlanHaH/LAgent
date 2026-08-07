from __future__ import annotations

from collections.abc import Awaitable, Callable
from typing import Protocol

from pydantic import BaseModel, ConfigDict, Field, SecretStr


class ModelCompletion(BaseModel):
    model_config = ConfigDict(extra="forbid")

    content: str
    model: str
    provider: str = "openai-compatible"
    prompt_tokens: int | None = None
    completion_tokens: int | None = None
    latency_ms: int
    first_token_latency_ms: int | None = None


DeltaCallback = Callable[[str], Awaitable[None]]


class ModelClient(Protocol):
    @property
    def configured(self) -> bool: ...

    @property
    def model_name(self) -> str: ...

    async def complete(
        self,
        system_prompt: str,
        user_prompt: str,
        *,
        max_output_tokens: int | None = None,
    ) -> ModelCompletion: ...

    async def complete_streaming(
        self,
        system_prompt: str,
        user_prompt: str,
        on_delta: DeltaCallback,
        *,
        max_output_tokens: int | None = None,
    ) -> ModelCompletion: ...

    async def close(self) -> None: ...


class CompletionRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    system_prompt: str = Field(alias="systemPrompt", min_length=1, max_length=20_000)
    user_prompt: str = Field(alias="userPrompt", min_length=1, max_length=50_000)
    max_output_tokens: int | None = Field(default=None, alias="maxOutputTokens", ge=1, le=8000)


class RuntimeModelConfiguration(BaseModel):
    """Internal runtime configuration supplied by the trusted Java backend."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    provider: str = Field(default="OPENAI_COMPATIBLE", min_length=1, max_length=60)
    base_url: str = Field(alias="baseUrl", min_length=1, max_length=500)
    api_key: SecretStr = Field(alias="apiKey", min_length=1)
    model_name: str = Field(alias="modelName", min_length=1, max_length=120)
    timeout_seconds: float = Field(default=60, alias="timeoutSeconds", gt=0, le=300)
    max_output_tokens: int = Field(default=1200, alias="maxOutputTokens", ge=1, le=8000)
    thinking: str = Field(default="disabled", pattern="^(enabled|disabled|)$")
    allow_http: bool = Field(default=False, alias="allowHttp")
