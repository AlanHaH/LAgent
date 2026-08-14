from __future__ import annotations

from app.model.schemas import DeltaCallback, ModelCompletion


class FakeModelClient:
    def __init__(self, *, answer: str | None = None, configured: bool = True) -> None:
        self.answer = answer
        self._configured = configured
        self.calls = 0
        self.user_prompts: list[str] = []

    @property
    def configured(self) -> bool:
        return self._configured

    @property
    def model_name(self) -> str:
        return "fake-model"

    async def close(self) -> None:
        return None

    async def complete(
        self,
        system_prompt: str,
        user_prompt: str,
        *,
        max_output_tokens: int | None = None,
    ) -> ModelCompletion:
        del max_output_tokens
        self.calls += 1
        self.user_prompts.append(user_prompt)
        return self._result(self._content(system_prompt))

    async def complete_streaming(
        self,
        system_prompt: str,
        user_prompt: str,
        on_delta: DeltaCallback,
        *,
        max_output_tokens: int | None = None,
    ) -> ModelCompletion:
        del max_output_tokens
        self.calls += 1
        self.user_prompts.append(user_prompt)
        content = self._content(system_prompt)
        midpoint = max(1, len(content) // 2)
        await on_delta(content[:midpoint])
        await on_delta(content[midpoint:])
        return self._result(content)

    def _content(self, system_prompt: str) -> str:
        if self.answer is not None:
            return self.answer
        if "画像访谈" in system_prompt:
            return (
                '{"assistantMessage":"我记下了你的安排。你更喜欢阅读文档还是先做练习？",'
                '"updates":{"directionQuery":"Java","currentStage":"BEGINNER",'
                '"planStartDate":null,"planEndDate":null,"planPeriodDays":90,'
                '"timezone":"Asia/Shanghai","weekStart":1,"backgroundText":null,'
                '"preference":null,"availability":null}}'
            )
        return "无状态认证不保存服务端会话，因此更容易水平扩展。[S1]"

    @staticmethod
    def _result(content: str) -> ModelCompletion:
        return ModelCompletion(
            content=content,
            model="fake-model",
            prompt_tokens=10,
            completion_tokens=20,
            latency_ms=12,
            first_token_latency_ms=3,
        )
