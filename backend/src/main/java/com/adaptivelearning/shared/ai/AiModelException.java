package com.adaptivelearning.shared.ai;

import com.adaptivelearning.shared.exception.ErrorCode;
import lombok.Getter;

import java.util.Map;

@Getter
public class AiModelException extends RuntimeException {
    private final ErrorCode code;
    private final String userMessage;
    private final Map<String, Object> details;

    public AiModelException(ErrorCode code) {
        this(code, null, Map.of(), null);
    }

    public AiModelException(ErrorCode code, Throwable cause) {
        this(code, null, Map.of(), cause);
    }

    public AiModelException(ErrorCode code, String userMessage, Map<String, Object> details, Throwable cause) {
        super(code.name(), cause);
        this.code = code;
        this.userMessage = userMessage;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }
}
