package com.adaptivelearning.support.application;

import com.adaptivelearning.evaluation.application.AssessmentService;
import com.adaptivelearning.shared.ai.PythonAiServiceClient;
import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;
import com.adaptivelearning.support.infrastructure.AuditLogMapper;
import com.adaptivelearning.support.infrastructure.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminPrivacyTest {
    @Mock UserMapper users;
    @Mock AuditLogMapper audits;
    @Mock JdbcTemplate jdbc;
    @Mock ObjectMapper json;
    @Mock AuditService audit;
    @Mock AssessmentService assessment;
    @Mock PythonAiServiceClient aiService;
    @Mock ModelSecretCipher modelSecrets;
    @Mock Environment environment;
    @InjectMocks AdminService service;

    @Test
    void rejectsPrivateDocumentContentWithoutQueryingChunks() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq("private-doc"))).thenReturn(0L);

        assertThatThrownBy(() -> service.adminDocumentContent("private-doc"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        verify(jdbc, never()).queryForList(anyString(), eq("private-doc"));
    }

    @Test
    void allowsPublicDocumentContent() {
        List<Map<String, Object>> chunks = List.of(Map.of("chunkNo", 1, "text", "public evidence"));
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq("public-doc"))).thenReturn(1L);
        when(jdbc.queryForList(anyString(), eq("public-doc"))).thenReturn(chunks);

        assertThat(service.adminDocumentContent("public-doc")).isEqualTo(chunks);
    }
}
