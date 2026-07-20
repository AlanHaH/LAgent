package com.adaptivelearning.shared.api;

import com.adaptivelearning.shared.web.RequestIdFilter;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

public record ErrorResponse(
        boolean success,
        ErrorBody error,
        String requestId,
        OffsetDateTime timestamp
) {
    public record ErrorBody(String code, String message, Map<String, Object> details,
                            List<FieldError> fieldErrors) {
    }

    public record FieldError(String field, String message) {
    }

    public static ErrorResponse of(String code, String message, Map<String, Object> details,
                                   List<FieldError> fieldErrors) {
        return new ErrorResponse(false, new ErrorBody(code, message, details, fieldErrors),
                RequestIdFilter.currentRequestId(), OffsetDateTime.now(ZoneOffset.UTC));
    }
}

