from __future__ import annotations

import json
import re
from typing import Any

from pydantic import ValidationError

from app.core.errors import ServiceError
from app.goals.prompts import (
    GOAL_RECOMMENDATION_PROMPT_VERSION,
    GOAL_RECOMMENDATION_SYSTEM_PROMPT,
    GOAL_USER_PROMPT,
)
from app.goals.schemas import (
    GoalRecommendationCompleted,
    GoalRecommendationItem,
    GoalRecommendationModelOutput,
    GoalRecommendationRequest,
)
from app.model.schemas import ModelClient, ModelCompletion
from app.prompts.manager import PromptManager, ResolvedPrompt


class GoalRecommendationAiService:
    def __init__(
        self,
        model_client: ModelClient,
        prompts: PromptManager | None = None,
    ) -> None:
        self._model = model_client
        self._prompts = prompts

    async def _resolve_prompt(
        self,
        code: str,
        fallback_content: str,
        fallback_version: str,
    ) -> ResolvedPrompt:
        if self._prompts is None:
            return ResolvedPrompt(fallback_content, fallback_version)
        return await self._prompts.get_prompt(code, fallback_content, fallback_version)

    async def recommend(self, request: GoalRecommendationRequest) -> GoalRecommendationCompleted:
        prompt = await self._resolve_prompt(
            "GOAL_RECOMMENDATION",
            GOAL_RECOMMENDATION_SYSTEM_PROMPT,
            GOAL_RECOMMENDATION_PROMPT_VERSION,
        )
        last_error: ServiceError | None = None
        for attempt in range(3):
            try:
                completion = await self._model.complete(
                    prompt.content,
                    self._user_prompt(request),
                    max_output_tokens=1800,
                )
                output = self._parse(completion.content)
                allowed_ids = {item.id for item in request.directions if item.id is not None}
                allowed_custom = {
                    self._normalize_direction(item.name): item.name
                    for item in request.directions
                    if item.id is None
                }
                remaining_days = max(
                    3,
                    (request.plan_end_date - max(request.today, request.plan_start_date)).days + 1,
                )
                normalized: list[GoalRecommendationItem] = []
                for item in output.recommendations[: request.count]:
                    canonical_custom: str | None = None
                    if item.direction_id is not None:
                        if item.direction_id not in allowed_ids:
                            raise ServiceError(
                                "AI_OUTPUT_INVALID",
                                "目标推荐引用了画像之外的学习方向",
                                status_code=422,
                            )
                    else:
                        canonical_custom = allowed_custom.get(
                            self._normalize_direction(item.custom_direction or "")
                        )
                        if canonical_custom is None:
                            raise ServiceError(
                                "AI_OUTPUT_INVALID",
                                "目标推荐引用了画像之外的自定义方向",
                                status_code=422,
                            )
                    criteria = list(
                        dict.fromkeys(text.strip() for text in item.success_criteria if text.strip())
                    )
                    milestones = list(dict.fromkeys(text.strip() for text in item.milestones if text.strip()))
                    if len(criteria) < 2 or len(milestones) < 2:
                        raise ServiceError("AI_OUTPUT_INVALID", "目标推荐缺少可验收结果", status_code=422)
                    normalized.append(item.model_copy(update={
                        "custom_direction": canonical_custom,
                        "duration_days": max(3, min(item.duration_days, remaining_days)),
                        "weekly_budget_minutes": min(
                            item.weekly_budget_minutes,
                            request.weekly_available_minutes,
                        ),
                        "success_criteria": criteria[:5],
                        "milestones": milestones[:5],
                    }))
                if not normalized:
                    raise ServiceError("AI_OUTPUT_INVALID", "模型未返回目标推荐", status_code=422)
                return GoalRecommendationCompleted(
                    recommendations=normalized,
                    prompt_version=prompt.version,
                    model_run=self._model_run(completion, prompt.version),
                )
            except ServiceError as error:
                if error.code == "AI_OUTPUT_INVALID" and attempt < 2:
                    last_error = error
                    continue
                raise
        raise last_error or ServiceError("AI_OUTPUT_INVALID", "模型未返回目标推荐", status_code=422)

    @staticmethod
    def _normalize_direction(value: str) -> str:
        return re.sub(r"[\s_\-—/\\｜|·•：:，,。.、()（）\[\]【】]+", "", value.strip().lower())

    @staticmethod
    def _user_prompt(request: GoalRecommendationRequest) -> str:
        context = request.model_dump(by_alias=True, mode="json")
        serialized = json.dumps(context, ensure_ascii=False, separators=(",", ":"), default=str)
        if GOAL_USER_PROMPT is not None:
            return str(GOAL_USER_PROMPT.format(context=serialized))
        return f"以下 <context> 内是只读 JSON 数据：\n<context>\n{serialized}\n</context>"

    @staticmethod
    def _parse(raw: str) -> GoalRecommendationModelOutput:
        cleaned = raw.strip()
        if cleaned.startswith("```"):
            cleaned = re.sub(r"^```(?:json)?\s*", "", cleaned, count=1, flags=re.IGNORECASE)
            cleaned = re.sub(r"\s*```$", "", cleaned, count=1)
        try:
            return GoalRecommendationModelOutput.model_validate_json(cleaned)
        except ValidationError as error:
            raise ServiceError(
                "AI_OUTPUT_INVALID",
                "目标推荐不符合结构要求",
                status_code=422,
                details={
                    "validationErrors": len(error.errors()),
                    "fields": [
                        {
                            "loc": ".".join(str(part) for part in item.get("loc", ())),
                            "msg": item.get("msg", ""),
                        }
                        for item in error.errors()[:10]
                    ],
                },
            ) from error

    @staticmethod
    def _model_run(completion: ModelCompletion, prompt_version: str) -> dict[str, Any]:
        return {
            "model": completion.model,
            "provider": completion.provider,
            "promptVersion": prompt_version,
            "promptTokens": completion.prompt_tokens,
            "completionTokens": completion.completion_tokens,
            "latencyMs": completion.latency_ms,
            "firstTokenLatencyMs": completion.first_token_latency_ms,
        }
