from __future__ import annotations

import json
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


class SequencePlanModel(RecordingPlanModel):
    def __init__(self, outputs: list[str]) -> None:
        super().__init__()
        self.outputs = list(outputs)
        self.calls = 0

    async def complete(
        self,
        system_prompt: str,
        user_prompt: str,
        *,
        max_output_tokens: int | None = None,
    ) -> ModelCompletion:
        del system_prompt, max_output_tokens
        self.user_prompts.append(user_prompt)
        output = self.outputs[min(self.calls, len(self.outputs) - 1)]
        self.calls += 1
        return ModelCompletion(content=output, model="fake-plan-model", latency_ms=5)


def plan_output(*knowledge_sequences: list[int]) -> str:
    return json.dumps(
        {
            "tasks": [
                {
                    "title": f"Task {index + 1}",
                    "taskType": "LEARNING",
                    "priority": "HIGH",
                    "estimatedMinutes": 30,
                    "knowledgePointIds": ids,
                    "sourceChunkIds": [],
                    "learningObjective": "Learn prerequisite content before dependent content",
                    "sourceQueries": [],
                    "acceptanceCriteria": ["Complete an explainable exercise"],
                    "reason": "Preserve the prerequisite learning order.",
                }
                for index, ids in enumerate(knowledge_sequences)
            ]
        }
    )


def business_plan_output(
    *, minutes: int = 45, goal_ids: list[str] | None = None,
    milestone_id: int | None = 9, milestone_ids: list[str] | None = None
) -> str:
    return json.dumps({"tasks": [{
        "clientRef": "task-11111111-1111-1111-1111-111111111111",
        "title": "Deliver milestone proof", "taskType": "PRACTICE", "priority": "HIGH",
        "estimatedMinutes": minutes, "knowledgePointIds": [], "sourceChunkIds": [],
        "learningObjective": "Produce an explainable project artifact", "sourceQueries": [],
        "acceptanceCriteria": ["Submit the artifact and verification notes"],
        "milestoneId": milestone_id, "coveredGoalCriterionIds": goal_ids or [],
        "coveredMilestoneCriterionIds": milestone_ids or [],
        "reason": "Cover the structured project acceptance criteria."
    }]})


def prerequisite_request(**updates) -> PlanRecommendationRequest:
    payload = {
        "userId": 1,
        "goalName": "Learn dependency graph",
        "directionName": "Computer science",
        "currentStage": "BEGINNER",
        "planStartDate": date(2026, 7, 27),
        "planEndDate": date(2026, 8, 2),
        "weeklyAvailableMinutes": 840,
        "count": 3,
        "knowledgePoints": [{"id": 1, "name": "A"}, {"id": 2, "name": "B"}],
        "knowledgeDependencies": [{"predecessorId": 1, "successorId": 2}],
        "satisfiedPrerequisiteIds": [],
    }
    payload.update(updates)
    return PlanRecommendationRequest.model_validate(payload)


@pytest.mark.asyncio
async def test_prerequisite_prompt_and_reverse_candidate_trigger_retry() -> None:
    model = SequencePlanModel([plan_output([2], [1]), plan_output([1], [2])])
    service = PlanRecommendationAiService(model)
    service._RETRY_BACKOFF_SECONDS = 0

    result = await service.recommend(prerequisite_request())

    assert model.calls == 2
    assert [task.knowledge_point_ids for task in result.tasks] == [[1], [2]]
    assert '"predecessorId": 1' in model.user_prompts[0]
    assert "A 是 B 的前置知识" in model.user_prompts[0]


@pytest.mark.asyncio
async def test_missing_unsatisfied_predecessor_is_rejected_after_retry() -> None:
    model = SequencePlanModel([plan_output([2])])
    service = PlanRecommendationAiService(model)
    service._RETRY_BACKOFF_SECONDS = 0

    with pytest.raises(ServiceError) as captured:
        await service.recommend(prerequisite_request())

    assert captured.value.code == "AI_OUTPUT_INVALID"
    assert model.calls == 3


@pytest.mark.asyncio
async def test_same_task_and_proficient_skip_are_allowed() -> None:
    same_task = PlanRecommendationAiService(SequencePlanModel([plan_output([1, 2])]))
    same_task._RETRY_BACKOFF_SECONDS = 0
    assert len((await same_task.recommend(prerequisite_request())).tasks) == 1

    proficient = PlanRecommendationAiService(SequencePlanModel([plan_output([2])]))
    proficient._RETRY_BACKOFF_SECONDS = 0
    result = await proficient.recommend(prerequisite_request(satisfiedPrerequisiteIds=[1]))
    assert result.tasks[0].knowledge_point_ids == [2]


@pytest.mark.asyncio
async def test_outside_knowledge_point_is_rejected_and_custom_direction_defaults_empty() -> None:
    invalid_model = SequencePlanModel([plan_output([99])])
    invalid = PlanRecommendationAiService(invalid_model)
    invalid._RETRY_BACKOFF_SECONDS = 0
    with pytest.raises(ServiceError) as captured:
        await invalid.recommend(prerequisite_request())
    assert captured.value.code == "AI_OUTPUT_INVALID"

    custom_request = prerequisite_request(
        explorationMode=True,
        knowledgePoints=[],
        knowledgeDependencies=[],
        satisfiedPrerequisiteIds=[],
    )
    custom = PlanRecommendationAiService(SequencePlanModel([plan_output([])]))
    result = await custom.recommend(custom_request)
    assert result.tasks[0].knowledge_point_ids == []


@pytest.mark.asyncio
async def test_goal_project_milestone_context_enters_prompt_and_valid_coverage_returns() -> None:
    model = SequencePlanModel([business_plan_output(
        goal_ids=["GC1"], milestone_ids=["M:ms-9:C1"]
    )])
    service = PlanRecommendationAiService(model)
    request = prerequisite_request(
        knowledgePoints=[], knowledgeDependencies=[], count=2,
        goalDescription="Build a real deliverable", goalType="PROJECT", goalPriority="HIGH",
        goalWeeklyBudgetMinutes=300, dailyRecommendedTasks=2, focusMinutes=45,
        goalSuccessCriteria=[{"criterionId": "GC1", "text": "Deliver working software"}],
        goalProfileVersion=10, schedulingProfileVersion=11,
        project={
            "id": 7, "publicId": "project-7", "name": "Demo project", "priority": "HIGH",
            "startDate": date(2026, 7, 27), "dueDate": date(2026, 8, 2),
            "deliverables": [{"name": "Demo"}],
            "milestones": [{
                "id": 9, "publicId": "ms-9", "sequenceNo": 1, "name": "Prototype",
                "dueDate": date(2026, 8, 1),
                "acceptanceCriteria": [{"criterionId": "M:ms-9:C1", "text": "Demo passes"}],
            }],
        },
    )

    result = await service.recommend(request)

    assert result.tasks[0].covered_goal_criterion_ids == ["GC1"]
    assert result.tasks[0].milestone_id == 9
    assert '"criterionId": "GC1"' in model.user_prompts[0]
    assert '"publicId": "ms-9"' in model.user_prompts[0]
    assert "软建议，不是上限" in model.user_prompts[0]
    assert "软偏好" in model.user_prompts[0]


@pytest.mark.asyncio
async def test_forged_coverage_and_milestone_are_retried_then_rejected() -> None:
    service = PlanRecommendationAiService(SequencePlanModel([
        business_plan_output(goal_ids=["GC-X"], milestone_id=99)
    ]))
    service._RETRY_BACKOFF_SECONDS = 0
    request = prerequisite_request(
        knowledgePoints=[], knowledgeDependencies=[],
        goalSuccessCriteria=[{"criterionId": "GC1", "text": "Done"}],
        project={
            "id": 7, "publicId": "project-7", "name": "Demo", "priority": "HIGH",
            "startDate": date(2026, 7, 27), "dueDate": date(2026, 8, 2), "milestones": []
        },
    )
    with pytest.raises(ServiceError) as captured:
        await service.recommend(request)
    assert captured.value.code == "AI_OUTPUT_INVALID"


def test_python_schema_rejects_180_minutes_without_clamping() -> None:
    from pydantic import ValidationError
    from app.plans.schemas import PlanRecommendationModelOutput

    with pytest.raises(ValidationError):
        PlanRecommendationModelOutput.model_validate_json(business_plan_output(minutes=180))


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
