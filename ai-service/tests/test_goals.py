from __future__ import annotations

from datetime import date

import pytest

from app.goals.schemas import GoalRecommendationRequest
from app.goals.service import GoalRecommendationAiService
from tests.fakes import FakeModelClient


@pytest.mark.asyncio
async def test_goal_recommendations_are_profile_bound_and_capacity_limited() -> None:
    model = FakeModelClient(answer=(
        '{"recommendations":[{"directionId":10,"name":"一周经济学基础入门",'
        '"type":"SKILL","description":"通过文档阅读、练习和测验建立经济学基础框架。",'
        '"priority":"MEDIUM","durationDays":30,"weeklyBudgetMinutes":1200,'
        '"successCriteria":["完成四章核心文档笔记","综合测验达到80分"],'
        '"reason":"适合零基础阶段和当前可用时间",'
        '"milestones":["完成概念地图","完成综合测验"]}]}'
    ))
    service = GoalRecommendationAiService(model)
    request = GoalRecommendationRequest.model_validate({
        "userId": 1,
        "today": date(2026, 7, 23),
        "profileVersionId": 99,
        "profileVersionNo": 2,
        "planStartDate": date(2026, 7, 23),
        "planEndDate": date(2026, 7, 29),
        "directions": [{"id": 10, "name": "经济学", "currentStage": "BEGINNER", "primary": True}],
        "weeklyAvailableMinutes": 840,
        "existingGoalNames": [],
        "count": 3,
    })

    result = await service.recommend(request)

    assert result.recommendations[0].direction_id == 10
    assert result.recommendations[0].duration_days == 7
    assert result.recommendations[0].weekly_budget_minutes == 840
    assert result.prompt_version == "goal-recommendation-v3-profile-snapshot"
    assert '"confidence":0.1' in model.user_prompts[0]
    assert '"recommendedDifficulty":1' in model.user_prompts[0]


@pytest.mark.asyncio
async def test_goal_recommendations_support_custom_profile_direction() -> None:
    model = FakeModelClient(answer=(
        '{"recommendations":[{"directionId":null,"customDirection":"心理学",'
        '"name":"一周心理学基础入门","type":"SKILL",'
        '"description":"通过文档阅读、概念图和练习建立心理学基础框架。",'
        '"priority":"MEDIUM","durationDays":7,"weeklyBudgetMinutes":600,'
        '"successCriteria":["完成核心概念图","完成一份案例分析"],'
        '"reason":"匹配零基础画像和一周周期",'
        '"milestones":["完成基础阅读笔记","完成案例分析"]}]}'
    ))
    service = GoalRecommendationAiService(model)
    request = GoalRecommendationRequest.model_validate({
        "userId": 1,
        "today": date(2026, 7, 23),
        "profileVersionId": 100,
        "profileVersionNo": 3,
        "planStartDate": date(2026, 7, 23),
        "planEndDate": date(2026, 7, 29),
        "directions": [{"id": None, "name": "心理学", "currentStage": "BEGINNER", "primary": True}],
        "weeklyAvailableMinutes": 840,
        "existingGoalNames": [],
        "count": 3,
    })

    result = await service.recommend(request)

    assert result.recommendations[0].direction_id is None
    assert result.recommendations[0].custom_direction == "心理学"
    assert result.prompt_version == "goal-recommendation-v3-profile-snapshot"


def test_goal_request_keeps_custom_direction_without_catalog_mapping() -> None:
    request = GoalRecommendationRequest.model_validate({
        "userId": 1,
        "today": date(2026, 7, 23),
        "profileVersionId": 100,
        "profileVersionNo": 3,
        "planStartDate": date(2026, 7, 23),
        "planEndDate": date(2026, 7, 29),
        "directions": [{"id": None, "name": " 经济 学自定义路线 ", "currentStage": "BEGINNER", "primary": True}],
        "weeklyAvailableMinutes": 840,
        "selfAssessmentCount": 2,
        "confidence": 0.2,
        "recommendedDifficulty": 2,
        "dailyRecommendedTasks": 2,
        "riskNotices": ["当前仅含自评证据，建议完成诊断"],
    })

    assert request.directions[0].id is None
    assert request.directions[0].name == " 经济 学自定义路线 "
    assert request.self_assessment_count == 2
