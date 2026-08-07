package com.adaptivelearning.evaluation.api;

import com.adaptivelearning.evaluation.application.AssessmentService;
import com.adaptivelearning.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/evaluation")
@RequiredArgsConstructor
public class EvaluationAdminController {
    private final AssessmentService assessments;

    public record ResolutionRequest(@NotNull Boolean accepted,
                                    @NotBlank @Size(max=2000) String resolution,
                                    BigDecimal correctedScore) { }

    @GetMapping("/appeals")
    public ApiResponse<List<Map<String,Object>>> appeals(@RequestParam(required=false) String status) {
        return ApiResponse.ok(assessments.adminAppeals(status));
    }

    @PostMapping("/appeals/{id}/resolution")
    public ApiResponse<Void> resolve(@PathVariable String id,@Valid @RequestBody ResolutionRequest request) {
        assessments.resolveAppeal(id,request.accepted(),request.resolution(),request.correctedScore());
        return ApiResponse.ok(null);
    }
}
