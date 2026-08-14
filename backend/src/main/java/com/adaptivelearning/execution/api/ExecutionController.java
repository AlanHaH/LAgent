package com.adaptivelearning.execution.api;

import com.adaptivelearning.execution.application.*;
import com.adaptivelearning.execution.domain.StudySessionEntity;
import com.adaptivelearning.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ExecutionController {
    private final TaskService tasks;
    private final StudySessionService sessions;
    private final LearningBlockService learningBlocks;

    public record UpdateTaskRequest(@Size(min = 2, max = 200) String title, @Size(max = 2000) String description,
                                    String priority, @Min(10) @Max(120) Integer estimatedMinutes,
                                    Instant scheduledStart, Instant dueAt, @NotNull Integer version) {
    }

    public record ActionRequest(@Size(max = 1000) String reason, boolean confirmed, boolean startTimer,
                                TaskService.CompletionInput summary,
                                TaskAcceptancePolicy.Confirmation acceptance) {
    }

    public record StartSessionRequest(@NotBlank String taskId) {
    }

    public record ManualSessionRequest(@NotNull Instant startedAt, @NotNull Instant endedAt,
                                       @NotBlank @Size(max = 1000) String reason) {
    }

    public record NoteRequest(@NotBlank @Size(max = 200) String title, @NotNull @Size(max = 100000) String markdown,
                              Integer version) {
    }

    public record BlockTestRequest(@NotNull Map<String, String> answers) {
    }

    public record BlockSourcesRequest(@NotEmpty @Size(max = 20) List<@NotBlank String> knowledgeSpaceIds) {
    }

    @GetMapping("/tasks")
    public ApiResponse<List<TaskService.TaskView>> list(@RequestParam(required = false) LocalDate date, @RequestParam(required = false) String status) {
        return ApiResponse.ok(tasks.list(date, status));
    }

    @GetMapping("/tasks/graph")
    public ApiResponse<TaskService.TaskGraphView> graph() {
        return ApiResponse.ok(tasks.graph());
    }

    @GetMapping("/tasks/{id}")
    public ApiResponse<TaskService.TaskView> get(@PathVariable String id) {
        return ApiResponse.ok(tasks.get(id));
    }

    @GetMapping("/tasks/{id}/learning-block")
    public ApiResponse<Map<String, Object>> learningBlock(@PathVariable String id) {
        return ApiResponse.ok(learningBlocks.byTask(id));
    }

    @PostMapping("/tasks/{id}/learning-block/generation")
    @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public ApiResponse<Map<String, Object>> generateLearningBlock(@PathVariable String id) {
        return ApiResponse.ok(learningBlocks.generate(id));
    }

    @PostMapping("/tasks/{id}/learning-block/sources")
    public ApiResponse<Map<String, Object>> attachLearningBlockSources(
            @PathVariable String id, @Valid @RequestBody BlockSourcesRequest request) {
        return ApiResponse.ok(learningBlocks.attachSources(id, request.knowledgeSpaceIds()));
    }

    @PostMapping("/learning-blocks/{id}/test-attempts")
    public ApiResponse<Map<String, Object>> submitBlockTest(@PathVariable String id,
                                                            @Valid @RequestBody BlockTestRequest request) {
        return ApiResponse.ok(learningBlocks.submit(id, request.answers()));
    }

    @PatchMapping("/tasks/{id}")
    public ApiResponse<TaskService.TaskView> update(@PathVariable String id, @Valid @RequestBody UpdateTaskRequest r) {
        return ApiResponse.ok(tasks.update(id, new TaskService.UpdateInput(r.title(), r.description(), r.priority(), r.estimatedMinutes(), r.scheduledStart(), r.dueAt(), r.version())));
    }

    @PostMapping("/tasks/{id}/start")
    public ApiResponse<StudySessionEntity> start(@PathVariable String id, @RequestBody(required = false) ActionRequest r) {
        return ApiResponse.ok(tasks.startTask(id, r == null || r.startTimer()));
    }

    @PostMapping("/tasks/{id}/{action:pause|resume|block|unblock|completion|completion-reversal|cancellation}")
    public ApiResponse<TaskService.TaskView> action(@PathVariable String id, @PathVariable String action, @RequestBody(required = false) ActionRequest r) {
        String target = switch (action) {
            case "pause", "completion-reversal" -> "PAUSED";
            case "resume", "unblock" -> "IN_PROGRESS";
            case "block" -> "BLOCKED";
            case "completion" -> "COMPLETED";
            default -> "CANCELED";
        };
        return ApiResponse.ok(tasks.transition(id, target, r == null ? null : r.reason(),
                r == null ? null : r.summary(), r != null && r.confirmed(),
                r == null ? null : r.acceptance()));
    }

    @GetMapping("/study-sessions/active")
    public ApiResponse<List<StudySessionService.ActiveSessionView>> activeSessions() {
        return ApiResponse.ok(sessions.active());
    }

    @PostMapping("/study-sessions")
    public ApiResponse<StudySessionEntity> session(@Valid @RequestBody StartSessionRequest r) {
        return ApiResponse.ok(sessions.start(r.taskId()));
    }

    @PostMapping("/study-sessions/{id}/pause")
    public ApiResponse<StudySessionEntity> pause(@PathVariable String id) {
        return ApiResponse.ok(sessions.pause(id));
    }

    @PostMapping("/study-sessions/{id}/resume")
    public ApiResponse<StudySessionEntity> resume(@PathVariable String id) {
        return ApiResponse.ok(sessions.resume(id));
    }

    @PostMapping("/study-sessions/{id}/stop")
    public ApiResponse<StudySessionService.SessionView> stop(@PathVariable String id) {
        return ApiResponse.ok(sessions.stop(id));
    }

    @GetMapping("/study-sessions/{id}")
    public ApiResponse<StudySessionService.SessionView> sessionGet(@PathVariable String id) {
        return ApiResponse.ok(sessions.get(id));
    }

    @PostMapping("/tasks/{id}/manual-study-sessions")
    public ApiResponse<StudySessionService.SessionView> manual(@PathVariable String id, @Valid @RequestBody ManualSessionRequest r) {
        return ApiResponse.ok(sessions.manual(id, r.startedAt(), r.endedAt(), r.reason()));
    }

    @GetMapping("/tasks/{id}/note")
    public ApiResponse<TaskService.NoteView> note(@PathVariable String id) {
        return ApiResponse.ok(tasks.note(id));
    }

    @PutMapping("/tasks/{id}/note")
    public ApiResponse<TaskService.NoteView> note(@PathVariable String id, @Valid @RequestBody NoteRequest r) {
        return ApiResponse.ok(tasks.saveNote(id, r.title(), r.markdown(), r.version()));
    }

    public record ChatRequest(@NotBlank @Size(max = 2000) String message,
                              @Size(max = 400) List<com.adaptivelearning.shared.ai.PythonAiServiceClient.TaskChatTurn> history) {
    }

    @PostMapping("/tasks/{id}/chats")
    public ApiResponse<TaskService.TaskChatResponse> chat(@PathVariable String id, @Valid @RequestBody ChatRequest r) {
        return ApiResponse.ok(tasks.chat(id, r.message(), r.history()));
    }

    @GetMapping("/tasks/{id}/chats")
    public ApiResponse<TaskService.TaskChatHistory> chats(@PathVariable String id) {
        return ApiResponse.ok(tasks.chatHistory(id));
    }

    @DeleteMapping("/tasks/{id}/chats")
    public ApiResponse<Void> clearChats(@PathVariable String id) {
        tasks.clearChat(id);
        return ApiResponse.ok(null);
    }
}
