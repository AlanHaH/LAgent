from __future__ import annotations

import pytest

from app.config import Settings
from app.model.runtime import RuntimeModelManager
from app.model.schemas import ModelCompletion, RuntimeModelConfiguration
from tests.fakes import FakeModelClient


class NamedFakeModel(FakeModelClient):
    def __init__(self, name: str) -> None:
        super().__init__(answer="OK")
        self.name = name

    @property
    def model_name(self) -> str:
        return self.name

    async def complete(
        self,
        system_prompt: str,
        user_prompt: str,
        *,
        max_output_tokens: int | None = None,
    ) -> ModelCompletion:
        del system_prompt, user_prompt, max_output_tokens
        return ModelCompletion(
            content="OK",
            model=self.name,
            prompt_tokens=3,
            completion_tokens=1,
            latency_ms=7,
        )


def base_settings() -> Settings:
    return Settings(
        env="test",
        internal_token="x" * 32,
        model_enabled=False,
        embedding_provider="hash",
        qdrant_mode="memory",
    )


def configuration() -> RuntimeModelConfiguration:
    return RuntimeModelConfiguration(
        baseUrl="https://models.example/v1",
        apiKey="secret",
        modelName="switch-target",
    )


@pytest.mark.asyncio
async def test_runtime_configuration_can_be_tested_without_switching() -> None:
    manager = RuntimeModelManager(
        base_settings(),
        initial_client=NamedFakeModel("initial"),
        client_factory=lambda settings: NamedFakeModel(settings.model_name),
    )
    result = await manager.test(configuration())

    assert result == {"ready": True, "model": "switch-target", "latencyMs": 7}
    assert manager.model_name == "initial"
    assert manager.source == "injected"


@pytest.mark.asyncio
async def test_runtime_configuration_switches_shared_model_client() -> None:
    manager = RuntimeModelManager(
        base_settings(),
        initial_client=NamedFakeModel("initial"),
        client_factory=lambda settings: NamedFakeModel(settings.model_name),
    )
    result = await manager.configure(configuration())

    assert result["ready"] is True
    assert result["model"] == "switch-target"
    assert manager.model_name == "switch-target"
    assert manager.source == "runtime"
