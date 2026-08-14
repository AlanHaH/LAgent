from __future__ import annotations

import asyncio
import json
import logging
from typing import Any

from pydantic import ValidationError

from app.core.errors import ServiceError
from app.model.schemas import ModelClient
from app.plans.prompts import (
    PLAN_RECOMMENDATION_PROMPT_VERSION,
    PLAN_RECOMMENDATION_SYSTEM_PROMPT,
    PLAN_M05B_INVARIANTS,
    PLAN_USER_TEMPLATE,
)
from app.plans.schemas import (
    PlanRecommendationCompleted,
    PlanRecommendationModelOutput,
    PlanRecommendationRequest,
    PlanTaskItem,
)
from app.prompts.manager import PromptManager, ResolvedPrompt
from app.rag.retrieval import RagRetrievalService
from app.rag.schemas import SearchRequest

logger = logging.getLogger(__name__)


class PlanRecommendationAiService:
    _TRANSIENT_ERRORS = {"AI_PROVIDER_ERROR", "AI_MODEL_TIMEOUT"}
    _MAX_ATTEMPTS = 3
    _RETRY_BACKOFF_SECONDS = 0.8

    def __init__(
        self,
        model_client: ModelClient,
        retrieval: RagRetrievalService | None = None,
        prompts: PromptManager | None = None,
    ) -> None:
        self._model = model_client
        self._retrieval = retrieval
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

    async def recommend(self, request: PlanRecommendationRequest) -> PlanRecommendationCompleted:
        prompt = await self._resolve_prompt(
            "PLAN_RECOMMENDATION",
            PLAN_RECOMMENDATION_SYSTEM_PROMPT,
            PLAN_RECOMMENDATION_PROMPT_VERSION,
        )
        evidence = await self._knowledge_evidence(request)
        allowed_chunk_ids = {int(item["chunkId"]) for item in evidence}
        last_error: ServiceError | None = None
        for attempt in range(self._MAX_ATTEMPTS):
            try:
                if not self._model.configured:
                    raise ServiceError("AI_DEPENDENCY_UNAVAILABLE", "AI 模型服务未配置，无法生成学习计划")
                completion = await self._model.complete(
                    f"{prompt.content}\n\n{PLAN_M05B_INVARIANTS}",
                    self._user_prompt(request, evidence),
                    max_output_tokens=4000,
                )
                output = self._parse(completion.content)
                normalized: list[PlanTaskItem] = []
                for item in output.tasks[: request.count]:
                    criteria = list(
                        dict.fromkeys(
                            text.strip()
                            for text in item.acceptance_criteria
                            if text.strip()
                        )
                    )
                    if not criteria:
                        raise ServiceError("AI_OUTPUT_INVALID", "学习任务缺少可验收结果")
                    kp_ids = self._normalize_knowledge_point_ids(
                        item.knowledge_point_ids, request
                    )
                    source_ids = list(
                        dict.fromkeys(
                            chunk_id
                            for chunk_id in item.source_chunk_ids
                            if chunk_id in allowed_chunk_ids
                        )
                    )[:12]
                    if request.allowed_space_ids and not source_ids:
                        raise ServiceError("AI_OUTPUT_INVALID", "知识库计划任务缺少有效资料引用")
                    normalized.append(
                        item.model_copy(
                            update={
                                "acceptance_criteria": criteria[:5],
                                "knowledge_point_ids": kp_ids[:10],
                                "source_chunk_ids": source_ids,
                                "source_queries": list(dict.fromkeys(
                                    query.strip() for query in item.source_queries if query.strip()
                                ))[:8],
                            }
                        )
                    )
                if not normalized:
                    raise ServiceError("AI_OUTPUT_INVALID", "模型未返回学习任务")
                self._validate_business_context(normalized, request)
                self._validate_prerequisites(normalized, request)
                return PlanRecommendationCompleted(
                    tasks=normalized,
                    prompt_version=prompt.version,
                    model_run=self._model_run(completion, prompt.version),
                )
            except ServiceError as error:
                should_retry = error.code in ("AI_OUTPUT_INVALID", "AI_DEPENDENCY_UNAVAILABLE")
                should_retry = should_retry or (error.code in self._TRANSIENT_ERRORS and error.retryable)
                if should_retry and attempt < self._MAX_ATTEMPTS - 1:
                    last_error = error
                    logger.warning(
                        "plan generation attempt %s/%s failed (code=%s, message=%s), retrying",
                        attempt + 1,
                        self._MAX_ATTEMPTS,
                        error.code,
                        error.message,
                    )
                    await asyncio.sleep(self._RETRY_BACKOFF_SECONDS * (attempt + 1))
                    continue
                raise
        raise last_error or ServiceError("AI_OUTPUT_INVALID", "模型未返回学习任务")

    async def _knowledge_evidence(
        self, request: PlanRecommendationRequest
    ) -> list[dict[str, Any]]:
        if not request.allowed_space_ids:
            return []
        if self._retrieval is None:
            raise ServiceError("AI_DEPENDENCY_UNAVAILABLE", "知识库检索服务未配置")
        base = " ".join(
            value
            for value in (
                request.goal_name,
                request.direction_name,
                request.current_stage,
                request.user_requirement or "",
                request.background_text or "",
            )
            if value
        )
        queries = [
            base,
            f"{request.direction_name} 目录 章节 核心概念 基础 前置知识",
            f"{request.goal_name} 练习 案例 复习 测验 可验收成果",
        ]
        results = await asyncio.gather(
            *[
                self._retrieval.search(
                    SearchRequest(
                        user_id=request.user_id,
                        query=query[:2000],
                        allowed_space_ids=request.allowed_space_ids,
                        allowed_document_version_ids=request.allowed_document_version_ids,
                        top_k=request.knowledge_top_k,
                        candidate_k=max(40, request.knowledge_top_k * 3),
                    )
                )
                for query in queries
            ]
        )
        unique: dict[int, Any] = {}
        for result in results:
            for hit in result.hits:
                previous = unique.get(hit.chunk_id)
                if previous is None or hit.score > previous.score:
                    unique[hit.chunk_id] = hit
        hits = sorted(unique.values(), key=lambda item: item.score, reverse=True)[:30]
        if not hits:
            raise ServiceError(
                "AI_EVIDENCE_INSUFFICIENT",
                "所选知识库没有检索到可用于规划的内容",
                status_code=422,
            )
        return [
            {
                "citationId": hit.citation_id,
                "chunkId": hit.chunk_id,
                "documentId": hit.document_id,
                "documentVersionId": hit.document_version_id,
                "titlePath": hit.title_path,
                "pageFrom": hit.page_from,
                "pageTo": hit.page_to,
                "content": hit.quote_preview,
            }
            for hit in hits
        ]

    def _user_prompt(
        self, request: PlanRecommendationRequest, evidence: list[dict[str, Any]]
    ) -> str:
        knowledge = ", ".join(f"{item.id}:{item.name}" for item in request.knowledge_points) or (
            "（无指定知识点，请按方向自行规划核心内容）"
        )
        goal_context = {
            "name": request.goal_name,
            "description": request.goal_description,
            "type": request.goal_type,
            "priority": request.goal_priority,
            "directionId": request.direction_id,
            "customDirection": request.custom_direction,
            "startDate": request.goal_start_date,
            "dueDate": request.goal_due_date,
            "weeklyBudgetMinutes": request.goal_weekly_budget_minutes,
            "successCriteria": [item.model_dump(by_alias=True) for item in request.goal_success_criteria],
            "goalProfileVersion": request.goal_profile_version,
            "schedulingProfileVersion": request.scheduling_profile_version,
        }
        project_context = request.project.model_dump(by_alias=True, mode="json") if request.project else None
        names_by_id = {item.id: item.name for item in request.knowledge_points}
        dependency_lines = [
            f"{names_by_id[item.predecessor_id]} 是 {names_by_id[item.successor_id]} 的前置知识"
            f"（{item.predecessor_id} → {item.successor_id}）"
            for item in request.knowledge_dependencies
        ]
        prerequisite_context = "\n".join(
            (
                "<knowledgePrerequisites>",
                "A → B 表示 A 是 B 的前置知识。",
                "knowledgeDependencies=" + json.dumps(
                    [item.model_dump(by_alias=True) for item in request.knowledge_dependencies],
                    ensure_ascii=False,
                ),
                "satisfiedPrerequisiteIds=" + json.dumps(
                    request.satisfied_prerequisite_ids, ensure_ascii=False
                ),
                *(dependency_lines or ["当前没有公共目录前置关系。"]),
                "未满足的前置知识必须先于后续知识或在同一任务中首次出现；"
                "已满足的前置知识可以跳过或安排 REVIEW。",
                "</knowledgePrerequisites>",
            )
        )
        evidence_json = (
            json.dumps(evidence, ensure_ascii=False)
            if evidence
            else "（未选择知识库，按目标、画像与知识点规划）"
        )
        extra: list[str] = []
        if request.background_text:
            extra.append(f"背景：{request.background_text}\n")
        if request.user_requirement:
            extra.append(f"用户的节奏要求：{request.user_requirement}\n")
        if request.exploration_mode:
            extra.append(
                "这是自定义方向，处于探索阶段：先划定知识边界和资料检索词；"
                "不要假装已有权威资料，sourceQueries 必须给出可执行检索建议。\n"
            )
        extra_lines = "".join(extra)
        if PLAN_USER_TEMPLATE is not None:
            return str(PLAN_USER_TEMPLATE.format(
                goal_name=request.goal_name,
                direction_name=request.direction_name,
                current_stage=request.current_stage,
                plan_start_date=request.plan_start_date,
                plan_end_date=request.plan_end_date,
                weekly_available_minutes=request.weekly_available_minutes,
                goal_context=json.dumps(goal_context, ensure_ascii=False, default=str),
                project_context=json.dumps(project_context, ensure_ascii=False, default=str),
                daily_recommended_tasks=request.daily_recommended_tasks,
                focus_minutes=request.focus_minutes,
                count=request.count,
                knowledge=knowledge,
                prerequisite_context=prerequisite_context,
                extra_lines=extra_lines,
                knowledge_evidence=evidence_json,
            ))
        parts = [
            f"目标：{request.goal_name}",
            f"方向：{request.direction_name}",
            f"当前阶段：{request.current_stage}",
            f"计划周期：{request.plan_start_date} 至 {request.plan_end_date}",
            f"每周可用学习时间：{request.weekly_available_minutes} 分钟",
            f"Goal业务上下文：{json.dumps(goal_context, ensure_ascii=False, default=str)}",
            f"Project/Milestone上下文：{json.dumps(project_context, ensure_ascii=False, default=str)}",
            f"学习节奏建议：每天约 {request.daily_recommended_tasks} 项任务（软建议，不是上限）",
            f"理想单次专注时长：{request.focus_minutes} 分钟（软偏好，正式范围仍为10～120分钟）",
            f"需要生成的任务数量：{request.count}",
            f"自定义方向探索模式：{request.exploration_mode}",
            f"可选知识点：{knowledge}",
            prerequisite_context,
            f"<knowledgeEvidence>\n{evidence_json}\n</knowledgeEvidence>",
        ]
        if request.background_text:
            parts.append(f"背景：{request.background_text}")
        if request.user_requirement:
            parts.append(f"用户的节奏要求：{request.user_requirement}")
        parts.append("请按学习逻辑生成任务序列，输出 JSON。")
        return "\n".join(parts)

    @staticmethod
    def _validate_business_context(
        tasks: list[PlanTaskItem], request: PlanRecommendationRequest
    ) -> None:
        goal_ids = {item.criterion_id for item in request.goal_success_criteria}
        milestones = {item.id: item for item in request.project.milestones} if request.project else {}
        seen_refs: set[str] = set()
        for task in tasks:
            if task.client_ref in seen_refs:
                raise ServiceError("AI_OUTPUT_INVALID", "模型返回了重复的 clientRef", status_code=422)
            seen_refs.add(task.client_ref)
            if not set(task.covered_goal_criterion_ids).issubset(goal_ids):
                raise ServiceError("AI_OUTPUT_INVALID", "模型创造了不存在的 Goal criterionId", status_code=422)
            if task.milestone_id is None:
                if task.covered_milestone_criterion_ids:
                    raise ServiceError("AI_OUTPUT_INVALID", "未归属里程碑的任务声明了里程碑覆盖", status_code=422)
                continue
            milestone = milestones.get(task.milestone_id)
            if milestone is None:
                raise ServiceError("AI_OUTPUT_INVALID", "模型创造了不存在的 milestoneId", status_code=422)
            criterion_ids = {item.criterion_id for item in milestone.acceptance_criteria}
            if not set(task.covered_milestone_criterion_ids).issubset(criterion_ids):
                raise ServiceError("AI_OUTPUT_INVALID", "模型创造了不存在的 Milestone criterionId", status_code=422)

    @staticmethod
    def _normalize_knowledge_point_ids(
        raw_ids: list[int | str], request: PlanRecommendationRequest
    ) -> list[int]:
        allowed_ids = {item.id for item in request.knowledge_points}
        ids_by_name = {item.name: item.id for item in request.knowledge_points}
        normalized: list[int] = []
        for raw_id in raw_ids:
            candidate: int | None = None
            if isinstance(raw_id, int):
                candidate = raw_id
            elif isinstance(raw_id, str):
                value = raw_id.strip()
                if value in ids_by_name:
                    candidate = ids_by_name[value]
                else:
                    try:
                        candidate = int(value)
                    except ValueError:
                        candidate = None
            if candidate is None or candidate not in allowed_ids:
                raise ServiceError(
                    "AI_OUTPUT_INVALID",
                    "模型返回了输入范围外的 knowledgePointId",
                    status_code=422,
                )
            normalized.append(candidate)
        return list(dict.fromkeys(normalized))[:10]

    @staticmethod
    def _validate_prerequisites(
        tasks: list[PlanTaskItem], request: PlanRecommendationRequest
    ) -> None:
        first_task: dict[int, int] = {}
        for task_index, task in enumerate(tasks):
            for knowledge_point_id in task.knowledge_point_ids:
                first_task.setdefault(int(knowledge_point_id), task_index)
        satisfied = set(request.satisfied_prerequisite_ids)
        for dependency in request.knowledge_dependencies:
            successor_task = first_task.get(dependency.successor_id)
            if successor_task is None or dependency.predecessor_id in satisfied:
                continue
            predecessor_task = first_task.get(dependency.predecessor_id)
            if predecessor_task is None:
                raise ServiceError(
                    "AI_OUTPUT_INVALID",
                    "后续知识点已出现，但未满足的前置知识点没有任务覆盖",
                    status_code=422,
                )
            if predecessor_task > successor_task:
                raise ServiceError(
                    "AI_OUTPUT_INVALID",
                    "模型任务顺序违反前置知识约束",
                    status_code=422,
                )

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
                    "fields": [
                        {
                            "loc": ".".join(str(p) for p in e.get("loc", ())),
                            "msg": e.get("msg", ""),
                        }
                        for e in error.errors()[:10]
                    ],
                },
            ) from error

    @staticmethod
    def _model_run(completion: Any, prompt_version: str) -> dict[str, Any]:
        return {
            "model": completion.model,
            "provider": completion.provider,
            "promptVersion": prompt_version,
            "promptTokens": completion.prompt_tokens,
            "completionTokens": completion.completion_tokens,
            "latencyMs": completion.latency_ms,
            "firstTokenLatencyMs": completion.first_token_latency_ms,
        }
