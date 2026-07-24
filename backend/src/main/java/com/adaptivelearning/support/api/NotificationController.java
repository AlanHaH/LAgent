package com.adaptivelearning.support.api;

import com.adaptivelearning.shared.api.ApiResponse;
import com.adaptivelearning.shared.api.PageResponse;
import com.adaptivelearning.support.application.NotificationService;
import com.adaptivelearning.support.domain.NotificationEntity;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class NotificationController {
    private final NotificationService service;

    public record PreferenceRequest(@NotNull Map<String, Boolean> values, Integer version) {
    }

    @GetMapping("/notifications")
    public ApiResponse<PageResponse<NotificationEntity>> list(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize, @RequestParam(required = false) Boolean unread) {
        return ApiResponse.ok(service.list(page, pageSize, unread));
    }

    @PostMapping("/notifications/{id}/read")
    public ApiResponse<Void> read(@PathVariable String id) {
        service.read(id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/notification-preferences")
    public ApiResponse<NotificationService.PreferenceView> preference() {
        return ApiResponse.ok(service.preference());
    }

    @PutMapping("/notification-preferences")
    public ApiResponse<NotificationService.PreferenceView> preference(@RequestBody PreferenceRequest r) {
        return ApiResponse.ok(service.savePreference(r.values(), r.version()));
    }
}
