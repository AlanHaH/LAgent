from __future__ import annotations

import json
import logging
import re
from typing import Any

from app.config import Settings
from app.core.errors import ServiceError
from app.model.schemas import ModelClient, ModelCompletion
from app.prompts.manager import PromptManager, ResolvedPrompt
from app.rag.retrieval import RagRetrievalService
from app.rag.schemas import SearchRequest, SearchResult
from app.taskchat.prompts import (
    TASK_CHAT_KNOWLEDGE_SYSTEM_PROMPT,
    TASK_CHAT_PROMPT_VERSION,
    TASK_CHAT_USER_TEMPLATE,
    TASK_CHAT_WEB_SYSTEM_PROMPT,
)
from app.taskchat.schemas import TaskChatCitation, TaskChatRequest, TaskChatResponse
from app.taskchat.search import WebSearcher

logger = logging.getLogger("ai-service")


class TaskChatAiService:
    _knowledge_citation = re.compile(r"\[(S[1-9][0-9]*)\]")
    _web_citation = re.compile(r"\[(W[1-9][0-9]*)\]")

    def __init__(
        self,
        settings: Settings,
        model_client: ModelClient,
        retrieval: RagRetrievalService,
        searcher: WebSearcher,
        prompts: PromptManager | None = None,
    ) -> None:
        self._settings = settings
        self._model = model_client
        self._retrieval = retrieval
        self._searcher = searcher
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

    async def chat(self, request: TaskChatRequest) -> TaskChatResponse:
        if not self._model.configured:
            raise ServiceError("AI_DEPENDENCY_UNAVAILABLE", "AI 模型服务未配置，无法回答")
        retrieval_result: SearchResult | None = None
        if request.allowed_space_ids:
            retrieval_result = await self._retrieval.search(
                SearchRequest(
                    user_id=request.user_id,
                    query=f"{request.task_title} {request.message}"[:2000],
                    allowed_space_ids=request.allowed_space_ids,
                    top_k=request.top_k,
                    candidate_k=max(20, request.top_k),
                )
            )
        if retrieval_result is not None and retrieval_result.evidence_sufficient and retrieval_result.hits:
            return await self._knowledge_answer(request, retrieval_result)
        return await self._web_answer(request)

    async def _knowledge_answer(self, request: TaskChatRequest, result: SearchResult) -> TaskChatResponse:
        evidence = [
            {
                "citationId": hit.citation_id,
                "quotePreview": hit.quote_preview,
                "titlePath": hit.title_path,
                "pageFrom": hit.page_from,
                "pageTo": hit.page_to,
            }
            for hit in result.hits
        ]
        prompt = self._user_prompt(request, "evidence", json.dumps(evidence, ensure_ascii=False))
        system = await self._resolve_prompt(
            "TASK_CHAT_KNOWLEDGE",
            TASK_CHAT_KNOWLEDGE_SYSTEM_PROMPT,
            TASK_CHAT_PROMPT_VERSION,
        )
        content, completion = await self._complete_with_retry(
            system.content,
            prompt,
            self._knowledge_citation,
            {hit.citation_id for hit in result.hits},
        )
        cited = set(dict.fromkeys(self._knowledge_citation.findall(content)))
        citations = [
            TaskChatCitation(
                citation_id=hit.citation_id,
                source_type="KNOWLEDGE",
                chunk_id=hit.chunk_id,
                quote_preview=hit.quote_preview[:300],
            )
            for hit in result.hits
            if hit.citation_id in cited
        ]
        return TaskChatResponse(
            answer=content,
            mode="KNOWLEDGE",
            citations=citations,
            model_run=self._model_run(completion, system.version),
        )

    async def _web_answer(self, request: TaskChatRequest) -> TaskChatResponse:
        results = await self._searcher.search(f"{request.task_title} {request.message}"[:120])
        sources = [
            {"citationId": f"W{index}", "title": item.title, "url": item.url, "snippet": item.snippet}
            for index, item in enumerate(results, 1)
        ]
        prompt = self._user_prompt(request, "sources", json.dumps(sources, ensure_ascii=False))
        allowed = {source["citationId"] for source in sources}
        system = await self._resolve_prompt(
            "TASK_CHAT_WEB",
            TASK_CHAT_WEB_SYSTEM_PROMPT,
            TASK_CHAT_PROMPT_VERSION,
        )
        content, completion = await self._complete_with_retry(
            system.content, prompt, self._web_citation, allowed
        )
        cited = set(dict.fromkeys(self._web_citation.findall(content)))
        citations = [
            TaskChatCitation(
                citation_id=source["citationId"],
                source_type="WEB",
                title=source["title"],
                url=source["url"],
                quote_preview=source["snippet"][:300],
            )
            for source in sources
            if source["citationId"] in cited
        ]
        return TaskChatResponse(
            answer=content,
            mode="WEB",
            citations=citations,
            model_run=self._model_run(completion, system.version),
        )

    async def _complete_with_retry(
        self,
        system_prompt: str,
        user_prompt: str,
        pattern: re.Pattern[str],
        allowed: set[str],
    ) -> tuple[str, ModelCompletion]:
        last_error: ServiceError | None = None
        for attempt in range(2):
            completion = await self._model.complete(
                system_prompt,
                user_prompt,
                max_output_tokens=min(self._settings.model_max_output_tokens, 1200),
            )
            content = completion.content.strip()
            mentioned = pattern.findall(content)
            if mentioned and all(item in allowed for item in mentioned):
                return content, completion
            last_error = ServiceError("AI_OUTPUT_INVALID", "AI 回答缺少有效引用", status_code=422)
            logger.info("task chat citation validation failed, attempt=%s", attempt + 1)
        raise last_error or ServiceError("AI_OUTPUT_INVALID", "AI 回答缺少有效引用", status_code=422)

    @staticmethod
    def _user_prompt(request: TaskChatRequest, source_tag: str, source_json: str) -> str:
        dialog = "\n".join(
            f"{'用户' if turn.role == 'USER' else '助手'}：{turn.content}" for turn in request.history
        )
        dialog_block = f"<dialog>\n{dialog}\n</dialog>\n" if dialog else ""
        task_type = request.task_type or "LEARNING"
        if TASK_CHAT_USER_TEMPLATE is not None:
            return str(TASK_CHAT_USER_TEMPLATE.format(
                task_title=request.task_title,
                task_type=task_type,
                dialog_block=dialog_block,
                source_tag=source_tag,
                source_json=source_json,
                message=request.message,
            ))
        parts = [
            f"<task>当前任务：{request.task_title}（{task_type}）</task>",
        ]
        if dialog:
            parts.append(f"<dialog>\n{dialog}\n</dialog>")
        parts.append(f"<{source_tag}>{source_json}</{source_tag}>")
        parts.append(f"<question>{request.message}</question>")
        return "\n".join(parts)

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
