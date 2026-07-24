package com.adaptivelearning.goalproject.api;

import com.adaptivelearning.goalproject.application.GoalProjectService;
import com.adaptivelearning.goalproject.application.GoalRecommendationService;
import com.adaptivelearning.goalproject.domain.*;
import com.adaptivelearning.shared.api.ApiResponse;
import com.adaptivelearning.shared.api.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class GoalProjectController {
    private final GoalProjectService service;
    private final GoalRecommendationService recommendationService;

    public record GoalRequest(@NotNull Long directionId, @Size(min = 2, max = 100) String name, @NotBlank String type,
                              @Size(max = 2000) String description,
                              @Pattern(regexp = "LOW|MEDIUM|HIGH|URGENT") String priority,
                              @NotNull LocalDate startDate, @NotNull LocalDate dueDate,
                              @Min(10) @Max(6720) int weeklyBudgetMinutes,
                              @NotEmpty List<Map<String, Object>> successCriteria, Integer version,
                              @Size(max = 1000) String changeReason,
                              @Pattern(regexp = "CUSTOM|AI_RECOMMENDED|RULE_RECOMMENDED") String sourceType,
                              Long profileVersionId, @Size(max = 80) String recommendationId,
                              @Size(max = 500) String recommendationReason) {
    }

    public record RecommendationRequest(@Min(1) @Max(3) Integer count) { }

    public record ActionRequest(@Size(max = 1000) String reason, boolean exceptionConfirmed) {
    }

    public record ProjectRequest(Long primaryDirectionId, @NotBlank @Size(max = 120) String name,
                                 @Size(max = 2000) String description,
                                 @NotNull LocalDate startDate, @NotNull LocalDate dueDate,
                                 @Pattern(regexp = "LOW|MEDIUM|HIGH|URGENT") String priority,
                                 List<Map<String, Object>> deliverables, @Size(max = 500) String repositoryUrl,
                                 Integer version) {
    }

    public record LinkRequest(@NotBlank String goalId,
                              @DecimalMin("0.0001") @DecimalMax("1.0") BigDecimal contributionWeight) {
    }

    public record MilestoneRequest(@NotBlank @Size(max = 120) String name, @Min(1) int sequenceNo,
                                   @NotNull LocalDate dueDate,
                                   @DecimalMin("0.0001") @DecimalMax("1.0") BigDecimal weight,
                                   @NotEmpty List<Map<String, Object>> acceptanceCriteria, Integer version) {
    }

    @GetMapping("/goals")
    public ApiResponse<PageResponse<LearningGoalEntity>> goals(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize, @RequestParam(required = false) String status) {
        return ApiResponse.ok(service.goals(page, pageSize, status));
    }

    @PostMapping("/goals")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<LearningGoalEntity> createGoal(@Valid @RequestBody GoalRequest r) {
        return ApiResponse.ok(service.createGoal(goal(r)));
    }

    @PostMapping("/goals/recommendations")
    public ApiResponse<GoalRecommendationService.RecommendationResponse> recommendations(
            @Valid @RequestBody(required = false) RecommendationRequest request) {
        return ApiResponse.ok(recommendationService.recommend(request == null || request.count() == null
                ? 3 : request.count()));
    }

    @GetMapping("/goals/{id}")
    public ApiResponse<LearningGoalEntity> goal(@PathVariable String id) {
        return ApiResponse.ok(service.getGoal(id));
    }

    @PatchMapping("/goals/{id}")
    public ApiResponse<LearningGoalEntity> updateGoal(@PathVariable String id, @Valid @RequestBody GoalRequest r) {
        return ApiResponse.ok(service.updateGoal(id, goal(r)));
    }

    @PostMapping("/goals/{id}/activation")
    public ApiResponse<LearningGoalEntity> activate(@PathVariable String id, @RequestBody(required = false) ActionRequest r) {
        return ApiResponse.ok(service.transitionGoal(id, GoalStatus.ACTIVE, reason(r), false));
    }

    @PostMapping("/goals/{id}/pause")
    public ApiResponse<LearningGoalEntity> pause(@PathVariable String id, @RequestBody(required = false) ActionRequest r) {
        return ApiResponse.ok(service.transitionGoal(id, GoalStatus.PAUSED, reason(r), false));
    }

    @PostMapping("/goals/{id}/resume")
    public ApiResponse<LearningGoalEntity> resume(@PathVariable String id, @RequestBody(required = false) ActionRequest r) {
        return ApiResponse.ok(service.transitionGoal(id, GoalStatus.ACTIVE, reason(r), false));
    }

    @PostMapping("/goals/{id}/completion")
    public ApiResponse<LearningGoalEntity> complete(@PathVariable String id, @RequestBody ActionRequest r) {
        return ApiResponse.ok(service.transitionGoal(id, GoalStatus.COMPLETED, r.reason(), r.exceptionConfirmed()));
    }

    @PostMapping("/goals/{id}/cancellation")
    public ApiResponse<LearningGoalEntity> cancel(@PathVariable String id, @RequestBody ActionRequest r) {
        return ApiResponse.ok(service.transitionGoal(id, GoalStatus.CANCELED, r.reason(), r.exceptionConfirmed()));
    }

    @GetMapping("/goals/{id}/progress")
    public ApiResponse<Map<String, Object>> progress(@PathVariable String id) {
        return ApiResponse.ok(service.progress(id));
    }

    @GetMapping("/projects")
    public ApiResponse<PageResponse<LearningProjectEntity>> projects(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize, @RequestParam(required = false) String status) {
        return ApiResponse.ok(service.projects(page, pageSize, status));
    }

    @PostMapping("/projects")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<LearningProjectEntity> createProject(@Valid @RequestBody ProjectRequest r) {
        return ApiResponse.ok(service.createProject(project(r)));
    }

    @GetMapping("/projects/{id}")
    public ApiResponse<LearningProjectEntity> project(@PathVariable String id) {
        return ApiResponse.ok(service.getProject(id));
    }

    @PatchMapping("/projects/{id}")
    public ApiResponse<LearningProjectEntity> updateProject(@PathVariable String id, @Valid @RequestBody ProjectRequest r) {
        return ApiResponse.ok(service.updateProject(id, project(r)));
    }

    @PostMapping("/projects/{id}/{action:activation|pause|resume|completion|cancellation|archive}")
    public ApiResponse<LearningProjectEntity> projectAction(@PathVariable String id, @PathVariable String action, @RequestBody(required = false) ActionRequest r) {
        ProjectStatus s = switch (action) {
            case "activation", "resume" -> ProjectStatus.ACTIVE;
            case "pause" -> ProjectStatus.PAUSED;
            case "completion" -> ProjectStatus.COMPLETED;
            case "cancellation" -> ProjectStatus.CANCELED;
            default -> ProjectStatus.ARCHIVED;
        };
        return ApiResponse.ok(service.transitionProject(id, s, reason(r), r != null && r.exceptionConfirmed()));
    }

    @PostMapping("/projects/{id}/goal-links")
    public ApiResponse<Void> link(@PathVariable String id, @Valid @RequestBody LinkRequest r) {
        service.linkGoal(id, r.goalId(), r.contributionWeight());
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/projects/{id}/goal-links/{goalId}")
    public ApiResponse<Void> unlink(@PathVariable String id, @PathVariable String goalId) {
        service.unlinkGoal(id, goalId);
        return ApiResponse.ok(null);
    }

    @GetMapping("/projects/{id}/milestones")
    public ApiResponse<List<MilestoneEntity>> milestones(@PathVariable String id) {
        return ApiResponse.ok(service.milestones(id));
    }

    @PostMapping("/projects/{id}/milestones")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MilestoneEntity> milestone(@PathVariable String id, @Valid @RequestBody MilestoneRequest r) {
        return ApiResponse.ok(service.addMilestone(id, milestone(r)));
    }

    @PatchMapping("/milestones/{id}")
    public ApiResponse<MilestoneEntity> milestoneUpdate(@PathVariable String id, @Valid @RequestBody MilestoneRequest r) {
        return ApiResponse.ok(service.updateMilestone(id, milestone(r)));
    }

    @PostMapping("/milestones/{id}/completion")
    public ApiResponse<MilestoneEntity> milestoneComplete(@PathVariable String id, @RequestBody Map<String, Object> evidence) {
        return ApiResponse.ok(service.completeMilestone(id, evidence));
    }

    private GoalProjectService.GoalInput goal(GoalRequest r) {
        return new GoalProjectService.GoalInput(r.directionId(), r.name(), r.type(), r.description(), r.priority(),
                r.startDate(), r.dueDate(), r.weeklyBudgetMinutes(), r.successCriteria(), r.version(),
                r.changeReason(), r.sourceType(), r.profileVersionId(), r.recommendationId(),
                r.recommendationReason());
    }

    private GoalProjectService.ProjectInput project(ProjectRequest r) {
        return new GoalProjectService.ProjectInput(r.primaryDirectionId(), r.name(), r.description(), r.startDate(), r.dueDate(), r.priority(), r.deliverables(), r.repositoryUrl(), r.version());
    }

    private GoalProjectService.MilestoneInput milestone(MilestoneRequest r) {
        return new GoalProjectService.MilestoneInput(r.name(), r.sequenceNo(), r.dueDate(), r.weight(), r.acceptanceCriteria(), r.version());
    }

    private String reason(ActionRequest r) {
        return r == null ? null : r.reason();
    }
}
