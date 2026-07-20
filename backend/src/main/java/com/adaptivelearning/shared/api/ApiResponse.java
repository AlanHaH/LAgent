package com.adaptivelearning.shared.api;

import com.adaptivelearning.shared.web.RequestIdFilter;

public record ApiResponse<T>(boolean success, T data, String requestId) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, RequestIdFilter.currentRequestId());
    }
}

