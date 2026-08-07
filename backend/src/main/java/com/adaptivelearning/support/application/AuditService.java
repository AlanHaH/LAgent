package com.adaptivelearning.support.application;

import com.adaptivelearning.shared.security.SecurityUtils;
import com.adaptivelearning.shared.web.RequestIdFilter;
import com.adaptivelearning.support.domain.AuditLogEntity;
import com.adaptivelearning.support.infrastructure.AuditLogMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class AuditService {
    private final AuditLogMapper mapper;
    private final HttpServletRequest request;

    public void record(String action, String resourceType, String resourceId,
                       String before, String after, String result) {
        recordAs(SecurityUtils.currentUserIdOrSystem(), RequestIdFilter.currentRequestId(), currentClientIp(),
                action, resourceType, resourceId, before, after, result);
    }

    /** 在请求线程内取好审计字段，供后台线程（如异步规划作业）显式传入 recordAs。 */
    public String currentClientIp() {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded == null ? request.getRemoteAddr() : forwarded.split(",")[0].trim();
    }

    /** Records work completed after the HTTP request thread has returned (for example SSE generation). */
    public void recordAs(long operatorId, String requestId, String ip, String action, String resourceType,
                         String resourceId, String before, String after, String result) {
        AuditLogEntity log = new AuditLogEntity();
        log.setRequestId(requestId == null ? "request-unavailable" : requestId);
        log.setOperatorId(operatorId);
        log.setOperatorType(operatorId == 0 ? "SYSTEM" : "USER");
        log.setAction(action);
        log.setResourceType(resourceType);
        log.setResourceId(resourceId);
        log.setBeforeSummary(truncate(before));
        log.setAfterSummary(truncate(after));
        log.setResult(result);
        log.setIp(ip);
        log.setCreatedAt(Instant.now());
        mapper.insert(log);
    }

    private String truncate(String value) {
        return value == null ? null : value.substring(0, Math.min(2000, value.length()));
    }
}
