package com.adaptivelearning.profile.api;

import com.adaptivelearning.profile.application.AvailabilityPolicy;
import com.adaptivelearning.profile.application.ProfileService;
import com.adaptivelearning.profile.application.ProfileInterviewService;
import com.adaptivelearning.profile.application.ProfileInterviewStreamService;
import com.adaptivelearning.profile.domain.ProfileGenerationJobEntity;
import com.adaptivelearning.profile.domain.ProfileVersionEntity;
import com.adaptivelearning.profile.domain.SelfAssessmentEntity;
import com.adaptivelearning.shared.api.ApiResponse;
import com.adaptivelearning.shared.security.SecurityUtils;
import com.adaptivelearning.shared.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
    private final ProfileInterviewService interviewService;
    private final ProfileInterviewStreamService interviewStreamService;

    public record DirectionRequest(Long directionId, @Size(max=120) String customDirection,
                                   @NotBlank @Size(max=80) String currentStage, boolean primary) {}
    public record ProfileRequest(@NotBlank String timezone, @Min(1) @Max(7) int weekStart,
                                 @Min(1) @Max(365) Integer planPeriodDays,
                                 LocalDate planStartDate, LocalDate planEndDate,
                                 @Size(max=2000) String backgroundText,
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
    public record InterviewStartRequest(Boolean restart) {}
    public record InterviewMessageRequest(@NotBlank @Size(max=2000) String content, @NotNull Integer version) {}
    public record InterviewConfirmRequest(@NotNull Integer version) {}
    public record ManualSaveRequest(@NotBlank String interviewSessionId, @NotNull Integer interviewVersion,
                                    @NotNull @Valid ProfileRequest profile,
                                    @NotNull @Valid PreferenceRequest preference,
                                    @NotNull @Valid AvailabilityRequest availability) {}

    @GetMapping public ApiResponse<ProfileService.ProfileView> get() { return ApiResponse.ok(service.get()); }

    @GetMapping("/interview-sessions/active")
    public ApiResponse<ProfileInterviewService.SessionView> activeInterview() {
        return ApiResponse.ok(interviewService.active());
    }

    @PostMapping("/interview-sessions") @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProfileInterviewService.SessionView> startInterview(
            @RequestBody(required = false) InterviewStartRequest request) {
        return ApiResponse.ok(interviewService.start(request != null && Boolean.TRUE.equals(request.restart())));
    }

    @GetMapping("/interview-sessions/{sessionId}")
    public ApiResponse<ProfileInterviewService.SessionView> interview(@PathVariable String sessionId) {
        return ApiResponse.ok(interviewService.get(sessionId));
    }

    @PostMapping("/interview-sessions/{sessionId}/messages")
    public ApiResponse<ProfileInterviewService.SessionView> interviewMessage(@PathVariable String sessionId,
            @Valid @RequestBody InterviewMessageRequest request) {
        return ApiResponse.ok(interviewService.addMessage(sessionId, request.content(), request.version()));
    }

    @PostMapping(value = "/interview-sessions/{sessionId}/messages",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public SseEmitter interviewMessageStream(@PathVariable String sessionId,
            @Valid @RequestBody InterviewMessageRequest request, HttpServletRequest servletRequest) {
        return interviewStreamService.stream(SecurityUtils.currentUserId(), RequestIdFilter.currentRequestId(),
                clientIp(servletRequest), sessionId, request.content(), request.version());
    }

    @PostMapping("/interview-sessions/{sessionId}/confirmation")
    public ApiResponse<ProfileInterviewService.ConfirmationView> confirmInterview(@PathVariable String sessionId,
            @Valid @RequestBody InterviewConfirmRequest request) {
        return ApiResponse.ok(interviewService.confirm(sessionId, request.version()));
    }

    @PostMapping("/manual-save")
    public ApiResponse<ProfileInterviewService.ManualSaveView> manualSave(
            @Valid @RequestBody ManualSaveRequest r) {
        ProfileRequest p = r.profile();
        PreferenceRequest pref = r.preference();
        return ApiResponse.ok(interviewService.saveManual(r.interviewSessionId(), r.interviewVersion(),
                new ProfileService.ProfileInput(p.timezone(), p.weekStart(), p.planPeriodDays(),
                        p.planStartDate(), p.planEndDate(), p.backgroundText(),
                        p.directions().stream().map(d -> new ProfileService.DirectionInput(
                                d.directionId(), d.customDirection(), d.currentStage(), d.primary())).toList(),
                        p.version()),
                new ProfileService.PreferenceInput(pref.contentModes(), pref.guidanceStyle(),
                        pref.taskGranularity(), pref.focusMinutes(), pref.capacityRatio(),
                        pref.difficultyMin(), pref.difficultyMax(), pref.reminders(), pref.version()),
                r.availability().slots().stream().map(s -> new AvailabilityPolicy.Slot(
                        s.weekday(), s.start(), s.end(), s.energyLevel())).toList()));
    }

    @PutMapping public ApiResponse<ProfileService.ProfileView> save(@Valid @RequestBody ProfileRequest r) {
        return ApiResponse.ok(service.save(new ProfileService.ProfileInput(r.timezone(), r.weekStart(), r.planPeriodDays(),
                r.planStartDate(), r.planEndDate(), r.backgroundText(),
                r.directions().stream().map(d -> new ProfileService.DirectionInput(d.directionId(),
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

    @GetMapping("/self-assessments")
    public ApiResponse<List<SelfAssessmentEntity>> selfAssessments() {
        return ApiResponse.ok(service.selfAssessments());
    }

    @GetMapping("/versions") public ApiResponse<List<ProfileVersionEntity>> versions() { return ApiResponse.ok(service.versions()); }

    @PostMapping("/generation-jobs") @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<ProfileGenerationJobEntity> generate() { return ApiResponse.ok(service.generate()); }

    @GetMapping("/generation-jobs/{jobId}")
    public ApiResponse<ProfileGenerationJobEntity> job(@PathVariable String jobId) { return ApiResponse.ok(service.getJob(jobId)); }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded == null ? request.getRemoteAddr() : forwarded.split(",")[0].trim();
    }
}
