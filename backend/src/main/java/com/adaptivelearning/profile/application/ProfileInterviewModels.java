package com.adaptivelearning.profile.application;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

public final class ProfileInterviewModels {
    private ProfileInterviewModels() {}

    public record PreferenceDraft(List<String> contentModes, String guidanceStyle, String taskGranularity,
                                  Integer focusMinutes, BigDecimal capacityRatio, Integer difficultyMin,
                                  Integer difficultyMax, Map<String, Boolean> reminders) {}

    public record SlotDraft(Integer weekday, LocalTime start, LocalTime end, String energyLevel) {}

    public record Draft(String timezone, Integer weekStart, LocalDate planStartDate, LocalDate planEndDate,
                        @JsonSerialize(using = ToStringSerializer.class) Long directionId,
                        String directionName, String customDirection, String currentStage,
                        String backgroundText, PreferenceDraft preference, List<SlotDraft> availability,
                        Map<String, String> evidence) {}

    public record DirectionOption(Long id, String code, String name) {}
    public record Transcript(String role, String content) {}
    public record AssistantTurn(String assistantMessage, Draft draft, String mode) {}
}
