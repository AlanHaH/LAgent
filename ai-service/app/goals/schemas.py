from __future__ import annotations

from datetime import date
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


class GoalDirectionContext(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    id: int | None = Field(default=None, gt=0)
    name: str = Field(min_length=1, max_length=120)
    current_stage: Literal["BEGINNER", "INTERMEDIATE", "ADVANCED"] = Field(alias="currentStage")
    primary: bool = False


class GoalPreferenceContext(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    content_modes: list[str] = Field(default_factory=list, alias="contentModes", max_length=4)
    guidance_style: str | None = Field(default=None, alias="guidanceStyle", max_length=40)
    task_granularity: str | None = Field(default=None, alias="taskGranularity", max_length=40)
    focus_minutes: int | None = Field(default=None, alias="focusMinutes", ge=10, le=180)
    difficulty_min: int | None = Field(default=None, alias="difficultyMin", ge=1, le=5)
    difficulty_max: int | None = Field(default=None, alias="difficultyMax", ge=1, le=5)


class GoalRecommendationRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    user_id: int = Field(alias="userId", gt=0)
    today: date
    profile_version_id: int = Field(alias="profileVersionId", gt=0)
    profile_version_no: int = Field(alias="profileVersionNo", ge=1)
    plan_start_date: date = Field(alias="planStartDate")
    plan_end_date: date = Field(alias="planEndDate")
    background_text: str | None = Field(default=None, alias="backgroundText", max_length=2000)
    directions: list[GoalDirectionContext] = Field(min_length=1, max_length=10)
    preference: GoalPreferenceContext | None = None
    weekly_available_minutes: int = Field(alias="weeklyAvailableMinutes", ge=10, le=10080)
    existing_goal_names: list[str] = Field(default_factory=list, alias="existingGoalNames", max_length=100)
    count: int = Field(default=3, ge=1, le=3)

    @model_validator(mode="after")
    def validate_context(self) -> GoalRecommendationRequest:
        if self.plan_end_date < self.plan_start_date:
            raise ValueError("planEndDate cannot precede planStartDate")
        if not any(item.id is not None for item in self.directions):
            raise ValueError("at least one catalog direction is required")
        return self


class GoalRecommendationItem(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    direction_id: int = Field(alias="directionId", gt=0)
    name: str = Field(min_length=2, max_length=100)
    type: Literal["SKILL", "EXAM", "PROJECT"]
    description: str = Field(min_length=10, max_length=1200)
    priority: Literal["LOW", "MEDIUM", "HIGH", "URGENT"] = "MEDIUM"
    duration_days: int = Field(alias="durationDays", ge=3, le=365)
    weekly_budget_minutes: int = Field(alias="weeklyBudgetMinutes", ge=10, le=6720)
    success_criteria: list[str] = Field(alias="successCriteria", min_length=2, max_length=5)
    reason: str = Field(min_length=5, max_length=500)
    milestones: list[str] = Field(min_length=2, max_length=5)


class GoalRecommendationModelOutput(BaseModel):
    model_config = ConfigDict(extra="ignore")

    recommendations: list[GoalRecommendationItem] = Field(min_length=1, max_length=3)


class GoalRecommendationCompleted(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    recommendations: list[GoalRecommendationItem]
    prompt_version: str = Field(alias="promptVersion")
    model_run: dict[str, Any] = Field(alias="modelRun")
