package com.adaptivelearning.support.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 校验 Python AI 服务拉取运行时系统提示词的内部端点与 X-Internal-Token 鉴权。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InternalPromptControllerIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Value("${app.ai-service.internal-token:}") String internalToken;

    @BeforeEach
    void seedActivatePrompt() {
        jdbc.update("""
                DELETE FROM prompt_template
                """);
        jdbc.update("""
                INSERT INTO prompt_template
                  (id, public_id, code, version_no, content, schema_json, status, created_at, created_by)
                VALUES
                  (90000001, 'prompt-goal-recommendation-v1', 'GOAL_RECOMMENDATION', 1,
                   '只返回一个 JSON 对象。', NULL, 'ACTIVE', CURRENT_TIMESTAMP, 0)
                """);
    }

    @Test
    void returnsActivePromptsForTrustedInternalToken() throws Exception {
        assertThat(internalToken).isNotBlank();
        mvc.perform(get("/internal/v1/prompt-templates").header("X-Internal-Token", internalToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].code").value("GOAL_RECOMMENDATION"))
                .andExpect(jsonPath("$.data[0].versionNo").value(1))
                .andExpect(jsonPath("$.data[0].content").value("只返回一个 JSON 对象。"));
    }

    @Test
    void rejectsMissingInternalTokenWith401() throws Exception {
        mvc.perform(get("/internal/v1/prompt-templates"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AUTH_UNAUTHENTICATED"));
    }

    @Test
    void rejectsWrongInternalTokenWith401() throws Exception {
        mvc.perform(get("/internal/v1/prompt-templates").header("X-Internal-Token", "wrong-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_UNAUTHENTICATED"));
    }

    @Test
    void nonInternalEndpointIsNotAffectedByFilter() throws Exception {
        // /api/v1 路径应走 JWT 认证（匿名仍 401，但由认证入口点返回，过滤器不拦截）。
        mvc.perform(get("/api/v1/profiles/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_UNAUTHENTICATED"));
    }
}
