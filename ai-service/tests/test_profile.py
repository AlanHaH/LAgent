from __future__ import annotations

import json
from datetime import date

import pytest

from app.profile.schemas import ProfileTurnRequest
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


def _collector(target: list[str]):  # type: ignore[no-untyped-def]
    async def collect(piece: str) -> None:
        target.append(piece)

    return collect
