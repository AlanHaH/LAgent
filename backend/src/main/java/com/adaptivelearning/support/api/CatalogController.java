package com.adaptivelearning.support.api;

import com.adaptivelearning.shared.api.ApiResponse;
import com.adaptivelearning.support.application.AdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CatalogController {
    private final AdminService service;

    @GetMapping("/learning-directions")
    public ApiResponse<List<Map<String, Object>>> directions() {
        return ApiResponse.ok(service.directions());
    }

    @GetMapping("/knowledge-points")
    public ApiResponse<List<Map<String, Object>>> knowledgePoints(@RequestParam(required = false) Long directionId) {
        List<Map<String, Object>> items = service.knowledgePoints();
        if (directionId != null) {
            items = items.stream().filter(item -> directionId.equals(((Number) item.get("direction_id")).longValue())).toList();
        }
        return ApiResponse.ok(items);
    }
}
