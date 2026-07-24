package com.adaptivelearning.shared.ai;

/** Raised when the browser closes or explicitly cancels an in-flight model stream. */
public class AiStreamCancelledException extends RuntimeException {
    public AiStreamCancelledException() {
        super("AI stream cancelled");
    }

    public AiStreamCancelledException(Throwable cause) {
        super("AI stream cancelled", cause);
    }
}
