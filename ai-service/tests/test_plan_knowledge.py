from __future__ import annotations

from datetime import date

import pytest

from app.core.errors import ServiceError
from app.model.schemas import ModelCompletion
from app.plans.schemas import PlanRecommendationRequest
from app.plans.service import PlanRecommendationAiService
from app.rag.schemas import SearchResult
from app.taskchat.schemas import TaskChatRequest
from app.taskchat.service import TaskChatAiService


class RecordingPlanModel:
    def __init__(self) -> None:
        self.user_prompts: list[str] = []

    @property
    def configured(self) -> bool:
        return True

    @property
    def model_name(self) -> str:
        return "fake-plan-model"

    async def complete(
        self,
        system_prompt: str,
        user_prompt: str,
        *,
        max_output_tokens: int | None = None,
    ) -> ModelCompletion:
        del system_prompt, max_output_tokens
        self.user_prompts.append(user_prompt)
        return ModelCompletion(
            content=(
                '{"tasks":[{"title":"Read chapter one","taskType":"LEARNING",'
                '"priority":"HIGH","estimatedMinutes":60,"knowledgePointIds":[],'
                '"sourceChunkIds":[101],"learningObjective":"Explain the chapter model",'
                '"sourceQueries":["official economics chapter one"],'
                '"acceptanceCriteria":["Write a concept map"],'
                '"reason":"Build the foundation before practice."}]}'
            ),
            model="fake-plan-model",
            latency_ms=5,
        )


class FakePlanRetrieval:
    def __init__(self) -> None:
        self.calls = []

    async def search(self, request):
        self.calls.append(request)
        return SearchResult.model_validate(
            {
                "evidenceSufficient": True,
                "hits": [
                    {
                        "citationId": "S1",
                        "chunkId": 101,
                        "documentId": 201,
                        "documentVersionId": 301,
                        "score": 0.91,
                        "vectorScore": 0.89,
                        "keywordScore": 0.75,
                        "titlePath": ["Chapter 1"],
                        "pageFrom": 2,
                        "pageTo": 3,
                        "quotePreview": "Supply and demand form the basic market model.",
                    }
                ],
                "embeddingModel": "fake-embedding",
                "collection": "test",
                "degraded": False,
                "latencyMs": 3,
            }
        )


class FlakyPlanModel(RecordingPlanModel):
    def __init__(self, failures: int) -> None:
        super().__init__()
        self.failures = failures

    async def complete(
        self,
        system_prompt: str,
        user_prompt: str,
        *,
        max_output_tokens: int | None = None,
    ) -> ModelCompletion:
        if self.failures:
            self.failures -= 1
            raise ServiceError(
                "AI_PROVIDER_ERROR",
                "provider temporarily unavailable",
                status_code=502,
                retryable=True,
            )
        return await super().complete(
            system_prompt,
            user_prompt,
            max_output_tokens=max_output_tokens,
        )


@pytest.mark.asyncio
async def test_plan_generation_uses_selected_knowledge_and_returns_chunk_sources() -> None:
    model = RecordingPlanModel()
    retrieval = FakePlanRetrieval()
    service = PlanRecommendationAiService(model, retrieval)
    request = PlanRecommendationRequest.model_validate(
        {
            "userId": 1,
            "goalName": "Learn economics",
            "directionName": "Economics",
            "currentStage": "BEGINNER",
            "planStartDate": date(2026, 7, 27),
            "planEndDate": date(2026, 8, 2),
            "allowedSpaceIds": [11],
            "allowedDocumentVersionIds": [301],
            "weeklyAvailableMinutes": 840,
            "count": 2,
        }
    )

    result = await service.recommend(request)

    assert len(retrieval.calls) == 3
    assert all(call.allowed_space_ids == [11] for call in retrieval.calls)
    assert all(call.allowed_document_version_ids == [301] for call in retrieval.calls)
    assert result.tasks[0].source_chunk_ids == [101]
    assert '"chunkId": 101' in model.user_prompts[0]
    assert "Supply and demand" in model.user_prompts[0]


@pytest.mark.asyncio
async def test_plan_generation_retries_transient_provider_failures() -> None:
    model = FlakyPlanModel(failures=2)
    service = PlanRecommendationAiService(model)
    request = PlanRecommendationRequest.model_validate(
        {
            "userId": 1,
            "goalName": "Learn economics",
            "directionName": "Economics",
            "currentStage": "BEGINNER",
            "planStartDate": date(2026, 7, 27),
            "planEndDate": date(2026, 8, 2),
            "weeklyAvailableMinutes": 840,
            "count": 2,
        }
    )

    result = await service.recommend(request)

    assert len(result.tasks) == 1
    assert model.failures == 0


def test_task_chat_prompt_keeps_history_beyond_the_old_six_turn_limit() -> None:
    request = TaskChatRequest.model_validate(
        {
            "userId": 1,
            "taskTitle": "Economics practice",
            "message": "Continue",
            "history": [
                {"role": "USER" if index % 2 == 0 else "ASSISTANT", "content": f"turn-{index}"}
                for index in range(20)
            ],
        }
    )

    prompt = TaskChatAiService._user_prompt(request, "sources", "[]")

    assert "turn-0" in prompt
    assert "turn-19" in prompt
