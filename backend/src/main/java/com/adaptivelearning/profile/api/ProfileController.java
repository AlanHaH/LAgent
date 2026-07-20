package com.adaptivelearning.profile.api;

import com.adaptivelearning.profile.application.AvailabilityPolicy;
import com.adaptivelearning.profile.application.ProfileService;
import com.adaptivelearning.profile.domain.ProfileGenerationJobEntity;
import com.adaptivelearning.profile.domain.ProfileVersionEntity;
import com.adaptivelearning.profile.domain.SelfAssessmentEntity;
import com.adaptivelearning.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/profiles/me")
@RequiredArgsConstructor
public class ProfileController {
    private final ProfileService service;

    public record DirectionRequest(Long directionId, @Size(max=120) String customDirection,
                                   @NotBlank @Size(max=80) String currentStage, boolean primary) {}
    public record ProfileRequest(@NotBlank String timezone, @Min(1) @Max(7) int weekStart,
                                 @Min(1) @Max(365) int planPeriodDays, @Size(max=2000) String backgroundText,
                                 @NotEmpty List<@Valid DirectionRequest> directions, Integer version) {}
    public record PreferenceRequest(@NotEmpty List<String> contentModes, @NotBlank String guidanceStyle,
                                    @NotBlank String taskGranularity, @Min(10) @Max(180) int focusMinutes,
                                    @NotNull BigDecimal capacityRatio, @Min(1) @Max(5) int difficultyMin,
                                    @Min(1) @Max(5) int difficultyMax, Map<String, Boolean> reminders, Integer version) {}
    public record AvailabilityRequest(@NotEmpty List<@Valid SlotRequest> slots) {}
    public record SlotRequest(@Min(1) @Max(7) int weekday, @NotNull LocalTime start, @NotNull LocalTime end,
                              @Pattern(regexp="LOW|MEDIUM|HIGH") String energyLevel) {}
    public record ExceptionRequest(@Min(0) @Max(960) int availableMinutes, @Size(max=500) String reason) {}
    public record SelfAssessmentRequest(@NotNull Long knowledgePointId, @Min(0) @Max(5) int level,
                                        LocalDate lastStudiedAt, @Size(max=1000) String note) {}

    @GetMapping public ApiResponse<ProfileService.ProfileView> get() { return ApiResponse.ok(service.get()); }

    @PutMapping public ApiResponse<ProfileService.ProfileView> save(@Valid @RequestBody ProfileRequest r) {
        return ApiResponse.ok(service.save(new ProfileService.ProfileInput(r.timezone(), r.weekStart(), r.planPeriodDays(),
                r.backgroundText(), r.directions().stream().map(d -> new ProfileService.DirectionInput(d.directionId(),
                d.customDirection(), d.currentStage(), d.primary())).toList(), r.version())));
    }

    @PutMapping("/preferences") public ApiResponse<ProfileService.PreferenceView> preference(@Valid @RequestBody PreferenceRequest r) {
        return ApiResponse.ok(service.savePreference(new ProfileService.PreferenceInput(r.contentModes(), r.guidanceStyle(),
                r.taskGranularity(), r.focusMinutes(), r.capacityRatio(), r.difficultyMin(), r.difficultyMax(), r.reminders(), r.version())));
    }

    @GetMapping("/availability") public ApiResponse<Map<String,Object>> availability() { return ApiResponse.ok(service.availability()); }

    @PutMapping("/availability") public ApiResponse<List<AvailabilityPolicy.NormalizedSlot>> availability(@Valid @RequestBody AvailabilityRequest r) {
        return ApiResponse.ok(service.saveAvailability(r.slots().stream().map(s -> new AvailabilityPolicy.Slot(
                s.weekday(), s.start(), s.end(), s.energyLevel())).toList()));
    }

    @PutMapping("/availability-exceptions/{date}") public ApiResponse<Void> exception(@PathVariable LocalDate date,
            @Valid @RequestBody ExceptionRequest r) { service.saveException(date, r.availableMinutes(), r.reason()); return ApiResponse.ok(null); }

    @PostMapping("/self-assessments") @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SelfAssessmentEntity> selfAssessment(@Valid @RequestBody SelfAssessmentRequest r) {
        return ApiResponse.ok(service.addSelfAssessment(r.knowledgePointId(), r.level(), r.lastStudiedAt(), r.note()));
    }

    @GetMapping("/versions") public ApiResponse<List<ProfileVersionEntity>> versions() { return ApiResponse.ok(service.versions()); }

    @PostMapping("/generation-jobs") @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<ProfileGenerationJobEntity> generate() { return ApiResponse.ok(service.generate()); }

    @GetMapping("/generation-jobs/{jobId}")
    public ApiResponse<ProfileGenerationJobEntity> job(@PathVariable String jobId) { return ApiResponse.ok(service.getJob(jobId)); }
}
