from __future__ import annotations

from datetime import date, time
from typing import Any, Literal
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

Stage = Literal["BEGINNER", "INTERMEDIATE", "ADVANCED"]
GuidanceStyle = Literal["SOCRATIC", "DIRECT"]
TaskGranularity = Literal["SMALL", "MEDIUM", "LARGE"]
ContentMode = Literal["TEXT", "PRACTICE"]
EnergyLevel = Literal["LOW", "MEDIUM", "HIGH"]


class ConversationMessage(BaseModel):
    model_config = ConfigDict(extra="forbid")

    role: Literal["USER", "ASSISTANT"]
    content: str = Field(min_length=1, max_length=2000)


class DirectionOption(BaseModel):
    model_config = ConfigDict(extra="allow")

    id: int | None = None
    name: str = Field(min_length=1, max_length=120)


class PreferenceUpdate(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    content_modes: list[ContentMode] | None = Field(
        default=None, alias="contentModes", min_length=1, max_length=2
    )
    guidance_style: GuidanceStyle | None = Field(default=None, alias="guidanceStyle")
    task_granularity: TaskGranularity | None = Field(default=None, alias="taskGranularity")
    focus_minutes: int | None = Field(default=None, alias="focusMinutes", ge=10, le=180)
    capacity_ratio: float | None = Field(default=None, alias="capacityRatio", ge=0.60, le=0.95)
    difficulty_min: int | None = Field(default=None, alias="difficultyMin", ge=1, le=5)
    difficulty_max: int | None = Field(default=None, alias="difficultyMax", ge=1, le=5)

    @model_validator(mode="after")
    def validate_difficulty(self) -> PreferenceUpdate:
        if (
            self.difficulty_min is not None
            and self.difficulty_max is not None
            and self.difficulty_min > self.difficulty_max
        ):
            raise ValueError("difficultyMin cannot exceed difficultyMax")
        return self


class AvailabilityUpdate(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    weekday: int = Field(ge=1, le=7)
    start: time
    end: time
    energy_level: EnergyLevel = Field(alias="energyLevel")

    @model_validator(mode="after")
    def validate_range(self) -> AvailabilityUpdate:
        if self.start == self.end:
            raise ValueError("availability start and end cannot be equal")
        return self


class ProfileUpdates(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    direction_query: str | None = Field(default=None, alias="directionQuery", max_length=120)
    current_stage: Stage | None = Field(default=None, alias="currentStage")
    plan_start_date: date | None = Field(default=None, alias="planStartDate")
    plan_end_date: date | None = Field(default=None, alias="planEndDate")
    plan_period_days: int | None = Field(default=None, alias="planPeriodDays", ge=1, le=365)
    timezone: str | None = Field(default=None, max_length=80)
    week_start: int | None = Field(default=None, alias="weekStart", ge=1, le=7)
    background_text: str | None = Field(default=None, alias="backgroundText", max_length=2000)
    preference: PreferenceUpdate | None = None
    availability: list[AvailabilityUpdate] | None = Field(default=None, max_length=21)

    @model_validator(mode="after")
    def validate_dates(self) -> ProfileUpdates:
        if self.plan_start_date and self.plan_end_date and self.plan_end_date < self.plan_start_date:
            raise ValueError("planEndDate cannot precede planStartDate")
        if self.plan_period_days and self.plan_start_date and self.plan_end_date:
            self.plan_period_days = None
        elif self.plan_period_days and self.plan_end_date:
            self.plan_period_days = None
        return self

    @field_validator("timezone")
    @classmethod
    def validate_timezone(cls, value: str | None) -> str | None:
        if value is None:
            return None
        try:
            ZoneInfo(value)
        except ZoneInfoNotFoundError as error:
            raise ValueError("timezone must be a valid IANA name") from error
        return value


class ProfileModelOutput(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    assistant_message: str = Field(alias="assistantMessage", min_length=1, max_length=1000)
    updates: ProfileUpdates


class ProfileTurnRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    user_id: int = Field(alias="userId", gt=0)
    session_id: str = Field(alias="sessionId", min_length=1, max_length=64)
    locale: str = Field(default="zh-CN", max_length=20)
    today: date
    current_draft: dict[str, Any] = Field(alias="currentDraft")
    direction_catalog: list[DirectionOption] = Field(alias="directionCatalog", max_length=200)
    recent_conversation: list[ConversationMessage] = Field(alias="recentConversation", max_length=10)
    latest_user_message: str = Field(alias="latestUserMessage", min_length=1, max_length=2000)

    @field_validator("current_draft")
    @classmethod
    def validate_draft_size(cls, value: dict[str, Any]) -> dict[str, Any]:
        if len(str(value)) > 20_000:
            raise ValueError("currentDraft is too large")
        return value

    @model_validator(mode="after")
    def validate_total_context(self) -> ProfileTurnRequest:
        size = len(str(self.current_draft)) + len(self.latest_user_message)
        size += sum(len(item.content) for item in self.recent_conversation)
        size += sum(len(item.name) for item in self.direction_catalog)
        if size > 30_000:
            raise ValueError("profile interview context is too large")
        return self


class ProfileTurnCompleted(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    assistant_message: str = Field(alias="assistantMessage")
    updates: ProfileUpdates
    prompt_version: str = Field(alias="promptVersion")
    model_run: dict[str, Any] = Field(alias="modelRun")
