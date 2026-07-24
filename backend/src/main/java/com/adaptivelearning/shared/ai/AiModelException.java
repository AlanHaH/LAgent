package com.adaptivelearning.shared.ai;

import com.adaptivelearning.shared.exception.ErrorCode;
import lombok.Getter;

@Getter
public class AiModelException extends RuntimeException {
    private final ErrorCode code;

    public AiModelException(ErrorCode code) {
        super(code.name());
        this.code = code;
    }

    public AiModelException(ErrorCode code, Throwable cause) {
        super(code.name(), cause);
        this.code = code;
    }
}
