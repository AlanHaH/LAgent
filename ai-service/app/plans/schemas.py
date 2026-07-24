from __future__ import annotations

from datetime import date
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


class PlanKnowledgePoint(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    id: int = Field(gt=0)
    name: str = Field(min_length=1, max_length=120)


class PlanRecommendationRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    user_id: int = Field(alias="userId", gt=0)
    goal_name: str = Field(alias="goalName", min_length=1, max_length=200)
    direction_name: str = Field(alias="directionName", min_length=1, max_length=120)
    current_stage: Literal["BEGINNER", "INTERMEDIATE", "ADVANCED"] = Field(alias="currentStage")
    plan_start_date: date = Field(alias="planStartDate")
    plan_end_date: date = Field(alias="planEndDate")
    background_text: str | None = Field(default=None, alias="backgroundText", max_length=2000)
    knowledge_points: list[PlanKnowledgePoint] = Field(default_factory=list, alias="knowledgePoints", max_length=50)
    user_requirement: str | None = Field(default=None, alias="userRequirement", max_length=1000)
    weekly_available_minutes: int = Field(alias="weeklyAvailableMinutes", ge=10, le=10080)
    count: int = Field(default=6, ge=2, le=10)

    @model_validator(mode="after")
    def validate_context(self) -> PlanRecommendationRequest:
        if self.plan_end_date < self.plan_start_date:
            raise ValueError("planEndDate cannot precede planStartDate")
        return self


class PlanTaskItem(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    title: str = Field(min_length=2, max_length=120)
    task_type: Literal["LEARNING", "PRACTICE", "REVIEW", "ASSESSMENT"] = Field(alias="taskType")
    priority: Literal["LOW", "MEDIUM", "HIGH", "URGENT"] = "MEDIUM"
    estimated_minutes: int = Field(alias="estimatedMinutes", ge=15, le=180)
    knowledge_point_ids: list[int | str] = Field(default_factory=list, alias="knowledgePointIds", max_length=10)
    acceptance_criteria: list[str] = Field(alias="acceptanceCriteria", min_length=1, max_length=5)
    reason: str = Field(min_length=5, max_length=500)


class PlanRecommendationModelOutput(BaseModel):
    model_config = ConfigDict(extra="ignore")

    tasks: list[PlanTaskItem] = Field(min_length=1, max_length=10)


class PlanRecommendationCompleted(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    tasks: list[PlanTaskItem]
    prompt_version: str = Field(alias="promptVersion")
    model_run: dict[str, Any] = Field(alias="modelRun")
