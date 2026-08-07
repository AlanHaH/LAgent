package com.adaptivelearning.evaluation.api;

import com.adaptivelearning.evaluation.application.*;
import com.adaptivelearning.evaluation.domain.*;
import com.adaptivelearning.shared.api.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class EvaluationController {
    private final AssessmentService assessments;
    private final AnalyticsService analytics;

    public record QuestionRequest(@NotBlank @Pattern(regexp = "SINGLE_CHOICE|MULTIPLE_CHOICE|TRUE_FALSE|FILL_BLANK|SHORT_ANSWER|ESSAY") String type, @NotBlank @Size(max = 4000) String stem, List<String> options,
                                  @NotNull Object answer, Map<String, Object> rubric, @Size(max = 4000) String analysis,
                                  @Min(1) @Max(5) int difficulty, @NotEmpty List<Long> knowledgePointIds) {
    }

    public record QuestionRef(@NotBlank String questionId, @DecimalMin("0.01") BigDecimal score) {
    }

    public record AssessmentRequest(@NotBlank String type, @NotBlank @Size(max = 200) String title,
                                    @Min(1) @Max(240) int durationMinutes, @Min(1) int maxAttempts,
                                    @DecimalMin("0") BigDecimal passScore,
                                    @NotEmpty List<@Valid QuestionRef> questions) {
    }

    public record DiagnosticRequest(@NotNull Long directionId, @Min(5) @Max(120) int durationMinutes,
                                    @Min(1) @Max(5) int difficulty) {
    }

    public record AnswerRequest(@NotNull Object answer) {
    }

    public record AppealRequest(@NotBlank @Size(max = 2000) String reason, Object evidence) {
    }

    public record CorrectionRequest(@NotBlank @Pattern(regexp = "CONCEPT_UNCLEAR|CARELESS|METHOD_WRONG|KNOWLEDGE_GAP|MISREAD|OTHER") String reasonCode) { }

    public record ReportRequest(@NotBlank String type, @NotNull LocalDate periodStart, @NotNull LocalDate periodEnd) {
    }

    @PostMapping("/questions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<QuestionEntity> question(@Valid @RequestBody QuestionRequest r) {
        return ApiResponse.ok(assessments.createQuestion(new AssessmentService.QuestionInput(r.type(), r.stem(), r.options(), r.answer(), r.rubric(), r.analysis(), r.difficulty(), r.knowledgePointIds(), "PRIVATE"), false));
    }

    @GetMapping("/questions/{id}")
    public ApiResponse<AssessmentService.QuestionView> question(@PathVariable String id) {
        return ApiResponse.ok(assessments.question(id, false));
    }

    @GetMapping("/assessments")
    public ApiResponse<PageResponse<AssessmentEntity>> list(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(assessments.list(page, pageSize));
    }

    @PostMapping("/assessments")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AssessmentEntity> assessment(@Valid @RequestBody AssessmentRequest r) {
        return ApiResponse.ok(assessments.create(new AssessmentService.AssessmentInput(r.type(), r.title(), r.durationMinutes(), r.maxAttempts(), r.passScore(), r.questions().stream().map(x -> new AssessmentService.QuestionRef(x.questionId(), x.score())).toList())));
    }

    @GetMapping("/assessments/{id}")
    public ApiResponse<AssessmentService.AssessmentDetail> assessment(@PathVariable String id) {
        return ApiResponse.ok(assessments.detail(id));
    }

    @PostMapping("/assessments/{id}/attempts")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AssessmentAttemptEntity> attempt(@PathVariable String id) {
        return ApiResponse.ok(assessments.start(id));
    }

    @PostMapping("/diagnostics")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AssessmentEntity> diagnostic(@Valid @RequestBody DiagnosticRequest r) {
        return ApiResponse.ok(assessments.diagnostic(r.directionId(), r.durationMinutes(), r.difficulty()));
    }

    @PostMapping("/diagnostics/{id}/attempts")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AssessmentAttemptEntity> diagnosticAttempt(@PathVariable String id) {
        return ApiResponse.ok(assessments.start(id));
    }

    @PutMapping("/attempts/{id}/answers/{sequence}")
    public ApiResponse<AttemptAnswerEntity> answer(@PathVariable String id, @PathVariable int sequence, @Valid @RequestBody AnswerRequest r) {
        return ApiResponse.ok(assessments.saveAnswer(id, sequence, r.answer()));
    }

    @PostMapping("/attempts/{id}/submission")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<AssessmentAttemptEntity> submit(@PathVariable String id, @RequestHeader("Idempotency-Key") String key) {
        return ApiResponse.ok(assessments.submit(id, key));
    }

    @PostMapping("/attempts/{id}/grading-retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<AssessmentAttemptEntity> retryGrading(@PathVariable String id) {
        return ApiResponse.ok(assessments.retryGrading(id));
    }

    @GetMapping("/attempts/{id}/result")
    public ApiResponse<AssessmentService.AttemptResult> result(@PathVariable String id) {
        return ApiResponse.ok(assessments.result(id));
    }

    @PostMapping("/attempt-answers/{id}/appeals")
    public ApiResponse<Void> appeal(@PathVariable String id, @Valid @RequestBody AppealRequest r) {
        assessments.appeal(id, r.reason(), r.evidence());
        return ApiResponse.ok(null);
    }

    @GetMapping("/assessment-appeals")
    public ApiResponse<List<Map<String,Object>>> appeals() {
        return ApiResponse.ok(assessments.appeals());
    }

    @PostMapping("/assessment-appeals/{id}/withdrawal")
    public ApiResponse<Void> withdrawAppeal(@PathVariable String id) {
        assessments.withdrawAppeal(id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/wrong-questions")
    public ApiResponse<List<WrongQuestionEntity>> wrong() {
        return ApiResponse.ok(assessments.wrongQuestions());
    }

    @PostMapping("/wrong-questions/{id}/correction")
    public ApiResponse<WrongQuestionEntity> correctWrong(@PathVariable long id,
                                                          @Valid @RequestBody CorrectionRequest request) {
        return ApiResponse.ok(assessments.correctWrong(id, request.reasonCode()));
    }

    @GetMapping("/mastery")
    public ApiResponse<List<KnowledgeMasteryEntity>> mastery() {
        return ApiResponse.ok(assessments.mastery());
    }

    @GetMapping("/analytics/overview")
    public ApiResponse<AnalyticsService.Overview> overview(@RequestParam LocalDate start, @RequestParam LocalDate end) {
        return ApiResponse.ok(analytics.overview(start, end));
    }

    @GetMapping("/analytics/study-time")
    public ApiResponse<List<AnalyticsService.DailyTime>> time(@RequestParam LocalDate start, @RequestParam LocalDate end) {
        return ApiResponse.ok(analytics.studyTime(start, end));
    }

    @GetMapping("/analytics/task-performance")
    public ApiResponse<Map<String, AnalyticsService.Metric>> tasks(@RequestParam LocalDate start, @RequestParam LocalDate end) {
        return ApiResponse.ok(analytics.taskPerformance(start, end));
    }

    @GetMapping("/analytics/mastery-trend")
    public ApiResponse<List<Map<String, Object>>> masteryTrend() {
        return ApiResponse.ok(analytics.masteryTrend());
    }

    @GetMapping("/reports")
    public ApiResponse<List<StudyReportEntity>> reports() {
        return ApiResponse.ok(analytics.reports());
    }

    @PostMapping("/reports/generation-jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<StudyReportEntity> report(@Valid @RequestBody ReportRequest r) {
        return ApiResponse.ok(analytics.generateReport(r.type(), r.periodStart(), r.periodEnd()));
    }

    @GetMapping("/reports/{id}")
    public ApiResponse<StudyReportEntity> report(@PathVariable String id) {
        return ApiResponse.ok(analytics.report(id));
    }
}
