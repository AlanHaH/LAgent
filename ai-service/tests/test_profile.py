from __future__ import annotations

import json
from datetime import date

import pytest

from app.core.errors import ServiceError
from app.model.schemas import ModelCompletion
from app.profile.schemas import ConversationMessage, ProfileTurnRequest
from app.profile.service import AssistantMessageProjector, ProfileInterviewAiService
from tests.fakes import FakeModelClient


def test_assistant_projector_handles_chunked_escapes() -> None:
    projector = AssistantMessageProjector()
    raw = json.dumps(
        {"assistantMessage": "第一行\n第二行：你好", "updates": {}},
        ensure_ascii=True,
    )
    visible = "".join(projector.accept(raw[index : index + 3]) for index in range(0, len(raw), 3))
    assert visible == "第一行\n第二行：你好"
    assert projector.emitted == visible


@pytest.mark.asyncio
async def test_profile_turn_streams_visible_text_and_validates_updates() -> None:
    service = ProfileInterviewAiService(FakeModelClient())
    deltas: list[str] = []
    request = ProfileTurnRequest.model_validate(
        {
            "userId": 1,
            "sessionId": "session-1",
            "today": date(2026, 7, 22),
            "currentDraft": {},
            "directionCatalog": [{"id": 1, "name": "Java"}],
            "recentConversation": [],
            "latestUserMessage": "我想学习 Java，计划三个月",
        }
    )

    result = await service.turn(request, _collector(deltas))

    assert "阅读文档还是先做练习" in "".join(deltas)
    assert result.updates.direction_query == "Java"
    assert result.updates.plan_period_days == 90


@pytest.mark.asyncio
async def test_profile_turn_repairs_invalid_structured_output() -> None:
    invalid = '{"assistantMessage":"我先确认你的水平。","updates":{"currentStage":"NOVICE"}}'
    repaired = (
        '{"assistantMessage":"我先确认你的水平。","updates":{"directionQuery":null,'
        '"currentStage":"BEGINNER","planStartDate":null,"planEndDate":null,'
        '"planPeriodDays":null,"timezone":null,"weekStart":null,"backgroundText":null,'
        '"preference":null,"availability":null}}'
    )
    model = SequencedModelClient([invalid, repaired])
    deltas: list[str] = []

    result = await ProfileInterviewAiService(model).turn(_request(), _collector(deltas))

    assert "".join(deltas) == "我先确认你的水平。"
    assert result.assistant_message == "我先确认你的水平。"
    assert result.updates.current_stage == "BEGINNER"
    assert result.model_run["repairAttempts"] == 1
    assert result.model_run["promptTokens"] == 20
    assert model.calls == 2


@pytest.mark.asyncio
async def test_profile_turn_reports_invalid_after_repair_attempts_are_exhausted() -> None:
    invalid = '{"assistantMessage":"继续。","updates":{"currentStage":"NOVICE"}}'
    model = SequencedModelClient([invalid, invalid, invalid])

    with pytest.raises(ServiceError) as captured:
        await ProfileInterviewAiService(model).turn(_request(), _collector([]))

    assert captured.value.code == "AI_OUTPUT_INVALID"
    assert captured.value.retryable is True
    assert captured.value.details["repairAttempts"] == 2
    assert captured.value.details["fields"] == ["updates.currentStage"]
    assert model.calls == 3


@pytest.mark.asyncio
async def test_weekly_frequency_does_not_invent_weekdays() -> None:
    model = FakeModelClient(answer=_availability_answer(range(1, 8)))

    result = await ProfileInterviewAiService(model).turn(
        _request("我一周学习三天，每次两小时"), _collector([])
    )

    assert result.updates.availability == []
    assert "请选择具体星期几" in result.assistant_message


@pytest.mark.asyncio
async def test_availability_keeps_only_weekdays_mentioned_by_user() -> None:
    model = FakeModelClient(answer=_availability_answer(range(1, 8)))

    result = await ProfileInterviewAiService(model).turn(
        _request("我每周一、三、五 19:00-21:00 有空"), _collector([])
    )

    assert [slot.weekday for slot in result.updates.availability or []] == [1, 3, 5]


@pytest.mark.asyncio
async def test_chinese_energy_level_keeps_cross_turn_availability() -> None:
    model = FakeModelClient(answer=_availability_answer(range(1, 6), energy_level="中等"))
    request = _request("星期一到星期五")
    request.recent_conversation = [
        ConversationMessage(role="USER", content="晚上8点到9点"),
        ConversationMessage(role="ASSISTANT", content="具体是星期几？"),
    ]

    result = await ProfileInterviewAiService(model).turn(request, _collector([]))

    assert [slot.weekday for slot in result.updates.availability or []] == [1, 2, 3, 4, 5]
    assert {slot.energy_level for slot in result.updates.availability or []} == {"MEDIUM"}
    assert result.model_run["repairAttempts"] == 0


def _availability_answer(days: range, *, energy_level: str = "MEDIUM") -> str:
    slots = [
        {"weekday": day, "start": "19:00", "end": "21:00", "energyLevel": energy_level}
        for day in days
    ]
    return json.dumps(
        {
            "assistantMessage": "我已经补全了你的每周安排。",
            "updates": {
                "directionQuery": None,
                "currentStage": None,
                "planStartDate": None,
                "planEndDate": None,
                "planPeriodDays": None,
                "timezone": None,
                "weekStart": None,
                "backgroundText": None,
                "preference": None,
                "availability": slots,
            },
        },
        ensure_ascii=False,
    )


def _request(message: str = "我是初学者") -> ProfileTurnRequest:
    return ProfileTurnRequest.model_validate(
        {
            "userId": 1,
            "sessionId": "session-repair",
            "today": date(2026, 7, 30),
            "currentDraft": {},
            "directionCatalog": [{"id": 1, "name": "Java"}],
            "recentConversation": [],
            "latestUserMessage": message,
        }
    )


class SequencedModelClient:
    def __init__(self, answers: list[str]) -> None:
        self.answers = answers
        self.calls = 0

    @property
    def configured(self) -> bool:
        return True

    @property
    def model_name(self) -> str:
        return "sequence-model"

    async def complete(
        self,
        system_prompt: str,
        user_prompt: str,
        *,
        max_output_tokens: int | None = None,
    ) -> ModelCompletion:
        del system_prompt, user_prompt, max_output_tokens
        return self._next()

    async def complete_streaming(
        self,
        system_prompt: str,
        user_prompt: str,
        on_delta,
        *,
        max_output_tokens: int | None = None,
    ) -> ModelCompletion:
        del system_prompt, user_prompt, max_output_tokens
        result = self._next()
        await on_delta(result.content)
        return result

    def _next(self) -> ModelCompletion:
        content = self.answers[self.calls]
        self.calls += 1
        return ModelCompletion(
            content=content,
            model=self.model_name,
            prompt_tokens=10,
            completion_tokens=20,
            latency_ms=12,
            first_token_latency_ms=3 if self.calls == 1 else None,
        )


def _collector(target: list[str]):  # type: ignore[no-untyped-def]
    async def collect(piece: str) -> None:
        target.append(piece)

    return collect
