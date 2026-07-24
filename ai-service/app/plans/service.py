from __future__ import annotations

import json
from typing import Any

from pydantic import ValidationError

from app.core.errors import ServiceError
from app.model.schemas import ModelClient
from app.plans.prompts import PLAN_RECOMMENDATION_PROMPT_VERSION, PLAN_RECOMMENDATION_SYSTEM_PROMPT
from app.plans.schemas import (
    PlanRecommendationCompleted,
    PlanRecommendationModelOutput,
    PlanRecommendationRequest,
    PlanTaskItem,
)


class PlanRecommendationAiService:
    def __init__(self, model_client: ModelClient) -> None:
        self._model = model_client

    async def recommend(self, request: PlanRecommendationRequest) -> PlanRecommendationCompleted:
        last_error: ServiceError | None = None
        for attempt in range(3):
            try:
                if not self._model.configured:
                    raise ServiceError("AI_DEPENDENCY_UNAVAILABLE", "AI 模型服务未配置，无法生成学习计划")
                completion = await self._model.complete(
                    PLAN_RECOMMENDATION_SYSTEM_PROMPT,
                    self._user_prompt(request),
                    max_output_tokens=1800,
                )
                output = self._parse(completion.content)
                allowed_ids = {item.id for item in request.knowledge_points}
                normalized: list[PlanTaskItem] = []
                for item in output.tasks[: request.count]:
                    criteria = list(dict.fromkeys(text.strip() for text in item.acceptance_criteria if text.strip()))
                    if not criteria:
                        raise ServiceError("AI_OUTPUT_INVALID", "学习任务缺少可验收结果")
                    kp_ids: list[int] = []
                    for kid in item.knowledge_point_ids:
                        if isinstance(kid, int) and kid in allowed_ids:
                            kp_ids.append(kid)
                        elif isinstance(kid, str):
                            matched = False
                            for kp in request.knowledge_points:
                                if kp.name == kid.strip():
                                    kp_ids.append(kp.id)
                                    matched = True
                                    break
                            if not matched:
                                try:
                                    int_id = int(kid)
                                    if int_id in allowed_ids:
                                        kp_ids.append(int_id)
                                except (TypeError, ValueError):
                                    pass
                    kp_ids = list(dict.fromkeys(kp_ids))[:10]
                    normalized.append(
                        item.model_copy(
                            update={
                                "acceptance_criteria": criteria[:5],
                                "knowledge_point_ids": kp_ids[:10],
                                "estimated_minutes": max(15, min(item.estimated_minutes, 180)),
                            }
                        )
                    )
                if not normalized:
                    raise ServiceError("AI_OUTPUT_INVALID", "模型未返回学习任务")
                return PlanRecommendationCompleted(
                    tasks=normalized,
                    prompt_version=PLAN_RECOMMENDATION_PROMPT_VERSION,
                    model_run=self._model_run(completion),
                )
            except ServiceError as error:
                if error.code in ("AI_OUTPUT_INVALID", "AI_DEPENDENCY_UNAVAILABLE") and attempt < 2:
                    last_error = error
                    continue
                raise
        raise last_error or ServiceError("AI_OUTPUT_INVALID", "模型未返回学习任务")

    def _user_prompt(self, request: PlanRecommendationRequest) -> str:
        knowledge = ", ".join(item.name for item in request.knowledge_points) or "（无指定知识点，请按方向自行规划核心内容）"
        parts = [
            f"目标：{request.goal_name}",
            f"方向：{request.direction_name}",
            f"当前阶段：{request.current_stage}",
            f"计划周期：{request.plan_start_date} 至 {request.plan_end_date}",
            f"每周可用学习时间：{request.weekly_available_minutes} 分钟",
            f"需要生成的任务数量：{request.count}",
            f"可选知识点：{knowledge}",
        ]
        if request.background_text:
            parts.append(f"背景：{request.background_text}")
        if request.user_requirement:
            parts.append(f"用户的节奏要求：{request.user_requirement}")
        parts.append("请按学习逻辑生成任务序列，输出 JSON。")
        return "\n".join(parts)

    def _parse(self, content: str) -> PlanRecommendationModelOutput:
        cleaned = content.strip()
        if cleaned.startswith("```"):
            first_newline = cleaned.find("\n")
            closing = cleaned.rfind("```")
            if first_newline > 0 and closing > first_newline:
                cleaned = cleaned[first_newline + 1 : closing].strip()
        try:
            data = json.loads(cleaned)
        except json.JSONDecodeError as error:
            raise ServiceError("AI_OUTPUT_INVALID", "模型输出不是有效 JSON") from error
        try:
            return PlanRecommendationModelOutput.model_validate(data)
        except ValidationError as error:
            raise ServiceError(
                "AI_OUTPUT_INVALID",
                "学习任务不符合结构要求",
                status_code=422,
                details={
                    "validationErrors": len(error.errors()),
                    "fields": [{"loc": ".".join(str(p) for p in e.get("loc", ())), "msg": e.get("msg", "")} for e in error.errors()[:10]],
                },
            ) from error

    @staticmethod
    def _model_run(completion: Any) -> dict[str, Any]:
        return {
            "model": completion.model,
            "provider": completion.provider,
            "promptVersion": PLAN_RECOMMENDATION_PROMPT_VERSION,
            "promptTokens": completion.prompt_tokens,
            "completionTokens": completion.completion_tokens,
            "latencyMs": completion.latency_ms,
            "firstTokenLatencyMs": completion.first_token_latency_ms,
        }
