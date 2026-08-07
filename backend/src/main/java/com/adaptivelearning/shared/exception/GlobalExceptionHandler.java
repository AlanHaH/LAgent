package com.adaptivelearning.shared.exception;

import com.adaptivelearning.shared.ai.AiModelException;
import com.adaptivelearning.shared.api.ErrorResponse;
import com.adaptivelearning.shared.web.RequestIdFilter;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.List;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> business(BusinessException ex) {
        return ResponseEntity.status(ex.getCode().status())
                .body(ErrorResponse.of(ex.getCode().name(), ex.getMessage(), ex.getDetails(), List.of()));
    }

    @ExceptionHandler(AiModelException.class)
    ResponseEntity<ErrorResponse> aiModel(AiModelException ex) {
        log.warn("AI model invocation failed: {}", ex.getCode());
        String message = ex.getUserMessage() == null || ex.getUserMessage().isBlank()
                ? aiModelMessage(ex.getCode()) : ex.getUserMessage();
        return ResponseEntity.status(ex.getCode().status())
                .body(ErrorResponse.of(ex.getCode().name(), message, ex.getDetails(), List.of()));
    }

    private static String aiModelMessage(ErrorCode code) {
        return switch (code) {
            case SERVICE_TEMPORARILY_UNAVAILABLE -> "AI 服务未启动或暂不可用，请启动 AI 服务后重试";
            case DOCUMENT_OCR_FAILED -> "扫描 PDF 文字识别失败，请检查文件清晰度后重试";
            case DOCUMENT_OCR_LIMIT_EXCEEDED -> "扫描 PDF 超过 OCR 页数或大小限制";
            case MODEL_OUTPUT_INVALID -> "AI 返回内容格式异常，系统自动修复失败，请重新发送本条消息";
            case MODEL_PROVIDER_ERROR -> "AI 模型服务返回错误，请稍后重试";
            case MODEL_REQUEST_TIMEOUT -> "AI 模型服务响应超时，请稍后重试";
            case MODEL_QUOTA_EXCEEDED -> "AI 模型调用额度已用尽";
            case WEREAD_NOT_LOGGED_IN -> "微信读书尚未登录，请先扫码或配置 API Key";
            case WEREAD_LOGIN_EXPIRED -> "微信读书登录已过期，请重新登录";
            case WEREAD_API_KEY_INVALID -> "微信读书 API Key 无效，请检查后重试";
            case WEREAD_QR_EXPIRED -> "微信读书登录二维码已过期，请重新获取";
            case WEREAD_REMOTE_ERROR -> "微信读书接口暂时不可用，请稍后重试";
            default -> "AI 服务暂时不可用";
        };
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException ex) {
        List<ErrorResponse.FieldError> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> new ErrorResponse.FieldError(e.getField(), e.getDefaultMessage()))
                .toList();
        log.warn("Request validation failed: requestId={} fields={}",
                RequestIdFilter.currentRequestId(), fields);
        return ResponseEntity.badRequest().body(ErrorResponse.of(ErrorCode.COMMON_VALIDATION_ERROR.name(),
                "请求参数校验失败", Map.of(), fields));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ErrorResponse> constraint(ConstraintViolationException ex) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(ErrorCode.COMMON_VALIDATION_ERROR.name(),
                ex.getMessage(), Map.of(), List.of()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> unreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(ErrorCode.COMMON_VALIDATION_ERROR.name(),
                "请求内容格式不正确", Map.of(), List.of()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorResponse> uploadTooLarge(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ErrorResponse.of(
                ErrorCode.FILE_SIZE_EXCEEDED.name(), "单文件不能超过 200 MB", Map.of(), List.of()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorResponse> denied() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse.of(
                ErrorCode.AUTH_FORBIDDEN.name(), "无权执行此操作", Map.of(), List.of()));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<ErrorResponse> duplicate(DuplicateKeyException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(
                ErrorCode.RESOURCE_VERSION_CONFLICT.name(), "资源已存在或版本发生冲突", Map.of(), List.of()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unknown(Exception ex) {
        log.error("Unhandled request failure", ex);
        return ResponseEntity.internalServerError().body(ErrorResponse.of(
                "COMMON_INTERNAL_ERROR", "服务暂时无法处理请求", Map.of(), List.of()));
    }
}
