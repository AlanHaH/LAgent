package com.adaptivelearning.planning.api;

import com.adaptivelearning.planning.application.PlanningService;
import com.adaptivelearning.planning.domain.PlanVersionEntity;
import com.adaptivelearning.planning.domain.PlanningJobEntity;
import com.adaptivelearning.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.ZonedDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class PlanningController {
    private final PlanningService service;
    public record JobRequest(String type,String projectId,@Size(max=2000) String userRequirement){}
    public record PublishRequest(@NotBlank String confirmationToken){}
    public record RejectRequest(@Size(max=1000) String reason){}
    public record PartialRequest(@NotEmpty List<String> selectedChangeIds){}
    public record RescheduleRequest(@NotNull ZonedDateTime scheduledStart,@NotNull ZonedDateTime dueAt,@NotBlank @Size(max=1000) String reason){}

    @PostMapping("/goals/{goalId}/planning-jobs") @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<PlanningService.JobView> job(@PathVariable String goalId,@Valid @RequestBody JobRequest r,@RequestHeader("Idempotency-Key") String key){return ApiResponse.ok(service.jobView(service.createJob(goalId,new PlanningService.JobRequest(r.type(),r.projectId(),r.userRequirement()),key)));}
    @PostMapping("/goals/{goalId}/optimization-requests") @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<PlanningService.JobView> optimize(@PathVariable String goalId,@Valid @RequestBody JobRequest r,@RequestHeader("Idempotency-Key") String key){return ApiResponse.ok(service.jobView(service.createJob(goalId,new PlanningService.JobRequest("OPTIMIZATION",r.projectId(),r.userRequirement()),key)));}
    @GetMapping("/planning-jobs/{jobId}") public ApiResponse<PlanningService.JobView> job(@PathVariable String jobId){return ApiResponse.ok(service.jobView(service.getJob(jobId)));}
    @GetMapping("/plans/{planId}") public ApiResponse<PlanningService.PlanDetail> plan(@PathVariable String planId){return ApiResponse.ok(service.getPlan(planId));}
    @GetMapping("/plans/{planId}/versions") public ApiResponse<List<PlanVersionEntity>> versions(@PathVariable String planId){return ApiResponse.ok(service.versions(planId));}
    @GetMapping("/plan-versions/{versionId}") public ApiResponse<PlanningService.VersionDetail> version(@PathVariable String versionId){return ApiResponse.ok(service.version(versionId));}
    @PostMapping("/plan-versions/{versionId}/validation") public ApiResponse<PlanningService.VersionDetail> validate(@PathVariable String versionId){return ApiResponse.ok(service.validate(versionId));}
    @PostMapping("/plan-versions/{versionId}/confirmation-requests") public ApiResponse<PlanningService.ConfirmationToken> confirm(@PathVariable String versionId){return ApiResponse.ok(service.requestConfirmation(versionId));}
    @PostMapping("/plan-versions/{versionId}/publication") public ApiResponse<PlanningService.PublicationResult> publish(@PathVariable String versionId,@Valid @RequestBody PublishRequest r,@RequestHeader("Idempotency-Key") String key){return ApiResponse.ok(service.publish(versionId,r.confirmationToken(),key));}
    @PostMapping("/plan-versions/{versionId}/partial-selection") public ApiResponse<PlanningService.VersionDetail> partial(@PathVariable String versionId,@Valid @RequestBody PartialRequest r){return ApiResponse.ok(service.partialSelection(versionId,r.selectedChangeIds()));}
    @PostMapping("/plan-versions/{versionId}/rejection") public ApiResponse<Void> reject(@PathVariable String versionId,@RequestBody(required=false) RejectRequest r){service.reject(versionId,r==null?null:r.reason());return ApiResponse.ok(null);}
    @PostMapping("/tasks/{taskId}/rescheduling-proposals") public ApiResponse<PlanningService.VersionDetail> reschedule(@PathVariable String taskId,@Valid @RequestBody RescheduleRequest r){return ApiResponse.ok(service.rescheduleProposal(taskId,r.scheduledStart(),r.dueAt(),r.reason()));}
}
