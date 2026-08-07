package com.adaptivelearning.support.api;

import com.adaptivelearning.evaluation.application.AssessmentService;
import com.adaptivelearning.evaluation.domain.QuestionEntity;
import com.adaptivelearning.shared.api.ApiResponse;
import com.adaptivelearning.shared.api.PageResponse;
import com.adaptivelearning.support.application.AdminService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {
    private final AdminService service;

    public record StatusRequest(@NotBlank String status, @NotNull Integer version,
                                @NotBlank @Size(max = 1000) String reason) {
    }

    public record UserRolesRequest(@NotEmpty Set<@NotBlank String> roles,
                                   @NotBlank @Size(max = 1000) String reason) {
    }

    public record DirectionRequest(Long id, Long parentId, @NotBlank String code, @NotBlank String name,
                                   @NotBlank String status, int sortNo, Integer version) {
    }

    public record KnowledgeRequest(Long id, @NotNull Long directionId, Long parentId,
                                   @NotBlank String code, @NotBlank String name,
                                   @Min(1) int level, @DecimalMin("0.0001") double defaultWeight,
                                   @NotBlank String status, Integer version) {
    }

    public record DependencyRequest(@NotNull Long predecessorId, @NotNull Long successorId) {
    }

    public record QuestionRequest(@NotBlank String type, @NotBlank @Size(max = 4000) String stem,
                                  List<String> options, @NotNull Object answer, Map<String, Object> rubric,
                                  @Size(max = 4000) String analysis, @Min(1) @Max(5) int difficulty,
                                  @NotEmpty List<Long> knowledgePointIds) {
    }

    public record ModelRequest(@NotBlank String provider, @NotBlank String providerName,
                               @NotBlank String baseUrl, String secretRef, @NotBlank String purpose,
                               @NotBlank String modelName, Map<String, Object> parameters,
                               @Min(1) int timeoutSeconds, @Min(1) long dailyLimit, Integer version) {
    }

    public record ResourceStatusRequest(@NotBlank String status, Integer version) {
    }

    public record PromptRequest(@NotBlank String code, @NotBlank String content, Object schema) {
    }

    @GetMapping("/users")
    @PreAuthorize("hasAuthority('user:read')")
    public ApiResponse<PageResponse<Map<String, Object>>> users(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(service.users(page, pageSize, status, keyword));
    }

    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('user:read')")
    public ApiResponse<List<Map<String, Object>>> roles() {
        return ApiResponse.ok(service.roles());
    }

    @GetMapping("/users/{id}/learning-file")
    @PreAuthorize("hasAuthority('user:read')")
    public ApiResponse<Map<String, Object>> learningFile(@PathVariable String id) {
        return ApiResponse.ok(service.learningFile(id));
    }

    @PostMapping("/users/{id}/status")
    @PreAuthorize("hasAuthority('user:status:write')")
    public ApiResponse<Void> status(@PathVariable String id, @Valid @RequestBody StatusRequest request) {
        service.userStatus(id, request.status(), request.version(), request.reason());
        return ApiResponse.ok(null);
    }

    @PutMapping("/users/{id}/roles")
    @PreAuthorize("hasAuthority('user:status:write')")
    public ApiResponse<Void> roles(@PathVariable String id, @Valid @RequestBody UserRolesRequest request) {
        service.updateUserRoles(id, request.roles(), request.reason());
        return ApiResponse.ok(null);
    }

    @GetMapping("/learning-directions")
    public ApiResponse<List<Map<String, Object>>> directions() {
        return ApiResponse.ok(service.directions());
    }

    @PostMapping("/learning-directions")
    @PreAuthorize("hasAuthority('direction:write')")
    public ApiResponse<Map<String, Object>> direction(@Valid @RequestBody DirectionRequest request) {
        return ApiResponse.ok(service.saveDirection(request.id(), request.parentId(), request.code(),
                request.name(), request.status(), request.sortNo(), request.version()));
    }

    @GetMapping("/knowledge-points")
    public ApiResponse<List<Map<String, Object>>> knowledge(
            @RequestParam(required = false) Long directionId,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(service.knowledgePoints(directionId, keyword));
    }

    @PostMapping("/knowledge-points")
    @PreAuthorize("hasAuthority('knowledge-point:write')")
    public ApiResponse<Map<String, Object>> knowledge(@Valid @RequestBody KnowledgeRequest request) {
        return ApiResponse.ok(service.saveKnowledge(request.id(), request.directionId(), request.parentId(),
                request.code(), request.name(), request.level(), request.defaultWeight(), request.status(),
                request.version()));
    }

    @GetMapping("/knowledge-dependencies")
    public ApiResponse<List<Map<String, Object>>> dependencies(
            @RequestParam(required = false) Long directionId) {
        return ApiResponse.ok(service.dependencies(directionId));
    }

    @PostMapping("/knowledge-dependencies")
    @PreAuthorize("hasAuthority('knowledge-point:write')")
    public ApiResponse<Void> dependency(@Valid @RequestBody DependencyRequest request) {
        service.dependency(request.predecessorId(), request.successorId());
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/knowledge-dependencies")
    @PreAuthorize("hasAuthority('knowledge-point:write')")
    public ApiResponse<Void> deleteDependency(@RequestParam long predecessorId,
                                              @RequestParam long successorId) {
        service.deleteDependency(predecessorId, successorId);
        return ApiResponse.ok(null);
    }

    @GetMapping("/questions")
    @PreAuthorize("hasAuthority('question:review')")
    public ApiResponse<PageResponse<Map<String, Object>>> questions(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(service.questions(page, pageSize, type, status, keyword));
    }

    @PostMapping("/questions")
    @PreAuthorize("hasAuthority('question:review')")
    public ApiResponse<QuestionEntity> question(@Valid @RequestBody QuestionRequest request) {
        return ApiResponse.ok(service.publicQuestion(new AssessmentService.QuestionInput(
                request.type(), request.stem(), request.options(), request.answer(), request.rubric(),
                request.analysis(), request.difficulty(), request.knowledgePointIds(), "PUBLIC")));
    }

    @GetMapping("/model-configs")
    @PreAuthorize("hasAuthority('model:config:write')")
    public ApiResponse<List<Map<String, Object>>> models() {
        return ApiResponse.ok(service.modelConfigs());
    }

    @PostMapping("/model-configs")
    @PreAuthorize("hasAuthority('model:config:write')")
    public ApiResponse<Map<String, Object>> model(@Valid @RequestBody ModelRequest request) {
        return ApiResponse.ok(service.saveModel(request.provider(), request.providerName(), request.baseUrl(),
                request.secretRef(), request.purpose(), request.modelName(), request.parameters(),
                request.timeoutSeconds(), request.dailyLimit()));
    }

    @PutMapping("/model-configs/{id}")
    @PreAuthorize("hasAuthority('model:config:write')")
    public ApiResponse<Map<String, Object>> model(@PathVariable String id,
                                                  @Valid @RequestBody ModelRequest request) {
        return ApiResponse.ok(service.updateModel(id, request.provider(), request.providerName(),
                request.baseUrl(), request.secretRef(), request.purpose(), request.modelName(),
                request.parameters(), request.timeoutSeconds(), request.dailyLimit(), request.version()));
    }

    @DeleteMapping("/model-configs/{id}")
    @PreAuthorize("hasAuthority('model:config:write')")
    public ApiResponse<Void> deleteModel(@PathVariable String id, @RequestParam Integer version) {
        service.deleteModel(id, version);
        return ApiResponse.ok(null);
    }

    @PatchMapping("/model-configs/{id}/status")
    @PreAuthorize("hasAuthority('model:config:write')")
    public ApiResponse<Void> modelStatus(@PathVariable String id,
                                         @Valid @RequestBody ResourceStatusRequest request) {
        service.updateModelStatus(id, request.status(), request.version());
        return ApiResponse.ok(null);
    }

    @PostMapping("/model-configs/{id}/test")
    @PreAuthorize("hasAuthority('model:config:write')")
    public ApiResponse<Map<String, Object>> testModel(@PathVariable String id) {
        return ApiResponse.ok(service.testModel(id));
    }

    @GetMapping("/prompt-templates")
    @PreAuthorize("hasAuthority('prompt:write')")
    public ApiResponse<List<Map<String, Object>>> prompts(@RequestParam(required = false) String code) {
        return ApiResponse.ok(service.prompts(code));
    }

    @PostMapping("/prompt-templates")
    @PreAuthorize("hasAuthority('prompt:write')")
    public ApiResponse<Map<String, Object>> prompt(@Valid @RequestBody PromptRequest request) {
        return ApiResponse.ok(service.savePrompt(request.code(), request.content(), request.schema()));
    }

    @PatchMapping("/prompt-templates/{id}/status")
    @PreAuthorize("hasAuthority('prompt:write')")
    public ApiResponse<Void> promptStatus(@PathVariable String id,
                                          @Valid @RequestBody ResourceStatusRequest request) {
        service.updatePromptStatus(id, request.status());
        return ApiResponse.ok(null);
    }

    @GetMapping("/audit-logs")
    @PreAuthorize("hasAuthority('audit:read')")
    public ApiResponse<PageResponse<Map<String, Object>>> audits(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(service.auditLogs(page, pageSize, action, result, keyword));
    }

    @GetMapping("/jobs")
    @PreAuthorize("hasAuthority('monitor:read')")
    public ApiResponse<Map<String, Object>> jobs(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(service.jobs(status, keyword));
    }

    @GetMapping("/system-metrics")
    @PreAuthorize("hasAuthority('monitor:read')")
    public ApiResponse<Map<String, Object>> metrics() {
        return ApiResponse.ok(service.metrics());
    }

    @GetMapping("/dashboard-charts")
    @PreAuthorize("hasAuthority('monitor:read')")
    public ApiResponse<Map<String, Object>> dashboardCharts() {
        return ApiResponse.ok(service.dashboardCharts());
    }

    @GetMapping("/knowledge-spaces")
    @PreAuthorize("hasAuthority('user:read')")
    public ApiResponse<List<Map<String, Object>>> knowledgeSpaces(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String userId) {
        return ApiResponse.ok(service.knowledgeSpaces(keyword, userId));
    }

    @GetMapping("/knowledge-spaces/{id}/documents")
    @PreAuthorize("hasAuthority('user:read')")
    public ApiResponse<List<Map<String, Object>>> knowledgeDocuments(@PathVariable String id) {
        return ApiResponse.ok(service.adminSpaceDocuments(id));
    }

    @GetMapping("/documents/{id}/content")
    @PreAuthorize("hasAuthority('user:read')")
    public ApiResponse<List<Map<String, Object>>> documentContent(@PathVariable String id) {
        return ApiResponse.ok(service.adminDocumentContent(id));
    }

    @GetMapping("/active-goals")
    @PreAuthorize("hasAuthority('user:read')")
    public ApiResponse<List<Map<String, Object>>> activeGoals() {
        return ApiResponse.ok(service.activeGoals());
    }
}
