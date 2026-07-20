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

@RestController @RequestMapping("/api/v1") @RequiredArgsConstructor
public class ExecutionController {
  private final TaskService tasks;private final StudySessionService sessions;
  public record UpdateTaskRequest(@Size(min=2,max=200)String title,@Size(max=2000)String description,String priority,@Min(10)@Max(120)Integer estimatedMinutes,Instant scheduledStart,Instant dueAt,@NotNull Integer version){}
  public record ActionRequest(@Size(max=1000)String reason,boolean confirmed,boolean startTimer,TaskService.CompletionInput summary){}
  public record StartSessionRequest(@NotBlank String taskId){}
  public record ManualSessionRequest(@NotNull Instant startedAt,@NotNull Instant endedAt,@NotBlank@Size(max=1000)String reason){}
  public record NoteRequest(@NotBlank@Size(max=200)String title,@NotNull@Size(max=100000)String markdown,Integer version){}
  @GetMapping("/tasks")public ApiResponse<List<TaskService.TaskView>>list(@RequestParam(required=false)LocalDate date,@RequestParam(required=false)String status){return ApiResponse.ok(tasks.list(date,status));}
  @GetMapping("/tasks/{id}")public ApiResponse<TaskService.TaskView>get(@PathVariable String id){return ApiResponse.ok(tasks.get(id));}
  @PatchMapping("/tasks/{id}")public ApiResponse<TaskService.TaskView>update(@PathVariable String id,@Valid@RequestBody UpdateTaskRequest r){return ApiResponse.ok(tasks.update(id,new TaskService.UpdateInput(r.title(),r.description(),r.priority(),r.estimatedMinutes(),r.scheduledStart(),r.dueAt(),r.version())));}
  @PostMapping("/tasks/{id}/start")public ApiResponse<StudySessionEntity>start(@PathVariable String id,@RequestBody(required=false)ActionRequest r){return ApiResponse.ok(tasks.startTask(id,r==null||r.startTimer()));}
  @PostMapping("/tasks/{id}/{action:pause|resume|block|unblock|completion|completion-reversal|cancellation}")public ApiResponse<TaskService.TaskView>action(@PathVariable String id,@PathVariable String action,@RequestBody(required=false)ActionRequest r){String target=switch(action){case"pause","completion-reversal"->"PAUSED";case"resume","unblock"->"IN_PROGRESS";case"block"->"BLOCKED";case"completion"->"COMPLETED";default->"CANCELED";};return ApiResponse.ok(tasks.transition(id,target,r==null?null:r.reason(),r==null?null:r.summary(),r!=null&&r.confirmed()));}
  @PostMapping("/study-sessions")public ApiResponse<StudySessionEntity>session(@Valid@RequestBody StartSessionRequest r){return ApiResponse.ok(sessions.start(r.taskId()));}
  @PostMapping("/study-sessions/{id}/pause")public ApiResponse<StudySessionEntity>pause(@PathVariable String id){return ApiResponse.ok(sessions.pause(id));}
  @PostMapping("/study-sessions/{id}/resume")public ApiResponse<StudySessionEntity>resume(@PathVariable String id){return ApiResponse.ok(sessions.resume(id));}
  @PostMapping("/study-sessions/{id}/stop")public ApiResponse<StudySessionService.SessionView>stop(@PathVariable String id){return ApiResponse.ok(sessions.stop(id));}
  @GetMapping("/study-sessions/{id}")public ApiResponse<StudySessionService.SessionView>sessionGet(@PathVariable String id){return ApiResponse.ok(sessions.get(id));}
  @PostMapping("/tasks/{id}/manual-study-sessions")public ApiResponse<StudySessionService.SessionView>manual(@PathVariable String id,@Valid@RequestBody ManualSessionRequest r){return ApiResponse.ok(sessions.manual(id,r.startedAt(),r.endedAt(),r.reason()));}
  @GetMapping("/tasks/{id}/note")public ApiResponse<TaskService.NoteView>note(@PathVariable String id){return ApiResponse.ok(tasks.note(id));}
  @PutMapping("/tasks/{id}/note")public ApiResponse<TaskService.NoteView>note(@PathVariable String id,@Valid@RequestBody NoteRequest r){return ApiResponse.ok(tasks.saveNote(id,r.title(),r.markdown(),r.version()));}
}
