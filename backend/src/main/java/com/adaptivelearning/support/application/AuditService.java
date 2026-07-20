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
        AuditLogEntity log = new AuditLogEntity();
        log.setRequestId(RequestIdFilter.currentRequestId());
        log.setOperatorId(SecurityUtils.currentUserIdOrSystem());
        log.setOperatorType(log.getOperatorId() == 0 ? "SYSTEM" : "USER");
        log.setAction(action);
        log.setResourceType(resourceType);
        log.setResourceId(resourceId);
        log.setBeforeSummary(truncate(before));
        log.setAfterSummary(truncate(after));
        log.setResult(result);
        log.setIp(clientIp());
        log.setCreatedAt(Instant.now());
        mapper.insert(log);
    }

    private String clientIp() {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded == null ? request.getRemoteAddr() : forwarded.split(",")[0].trim();
    }

    private String truncate(String value) {
        return value == null ? null : value.substring(0, Math.min(2000, value.length()));
    }
}

