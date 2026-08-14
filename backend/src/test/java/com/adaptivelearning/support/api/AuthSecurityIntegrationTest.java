package com.adaptivelearning.support.api;

import com.adaptivelearning.support.domain.UserEntity;
import com.adaptivelearning.support.infrastructure.UserMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthSecurityIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserMapper users;
    @Autowired PasswordEncoder passwords;

    @Test
    void disabledUserImmediatelyLosesAccessAndCannotRefresh() throws Exception {
        Login login = createAndLogin("disabled_user");
        Login admin = createAndLogin("disable_admin");
        grantAdmin(admin.userId());

        mvc.perform(get("/api/v1/admin/users").header("Authorization", bearer(admin.accessToken())))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/users/{id}/status", login.publicId())
                        .header("Authorization", bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\",\"version\":" + login.version()
                                + ",\"reason\":\"security test\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(login.accessToken())))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + login.refreshToken() + "\",\"deviceId\":\"test\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_UNAUTHENTICATED"));
    }

    @Test
    void removingAdminRoleImmediatelyRevokesOldTokenAuthorities() throws Exception {
        Login admin = createAndLogin("role_admin");
        grantAdmin(admin.userId());

        mvc.perform(get("/api/v1/admin/users").header("Authorization", bearer(admin.accessToken())))
                .andExpect(status().isOk());
        jdbc.update("DELETE FROM sys_user_role WHERE user_id=? AND role_id=2", admin.userId());
        mvc.perform(get("/api/v1/admin/users").header("Authorization", bearer(admin.accessToken())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AUTH_FORBIDDEN"));
    }

    @Test
    void disabledRoleDoesNotContributeRolesOrPermissions() throws Exception {
        Login login = createAndLogin("disabled_role_user");
        jdbc.update("INSERT INTO sys_role(id,code,name,status) VALUES(90,'DISABLED_TEST','disabled test','DISABLED')");
        jdbc.update("INSERT INTO sys_permission(id,code,name,resource_type) VALUES(90,'disabled:test','disabled test','TEST')");
        jdbc.update("INSERT INTO sys_role_permission(role_id,permission_id) VALUES(90,90)");
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,90)", login.userId());

        assertThat(users.findRoleCodes(login.userId())).doesNotContain("DISABLED_TEST");
        assertThat(users.findPermissionCodes(login.userId())).doesNotContain("disabled:test");
    }

    @Test
    void refreshTokenRotatesAndCannotBeReplayed() throws Exception {
        Login login = createAndLogin("refresh_user");
        String response = mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + login.refreshToken() + "\",\"deviceId\":\"test\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String replacement = json.readTree(response).path("data").path("refreshToken").asText();
        assertThat(replacement).isNotBlank().isNotEqualTo(login.refreshToken());

        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + login.refreshToken() + "\",\"deviceId\":\"test\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_TOKEN_EXPIRED"));
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + replacement + "\",\"deviceId\":\"test\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void logoutAndLogoutAllRevokeRefreshTokens() throws Exception {
        Login single = createAndLogin("logout_user");
        mvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + single.refreshToken() + "\"}"))
                .andExpect(status().isOk());
        assertRefreshRejected(single.refreshToken());

        Login all = createAndLogin("logout_all_user");
        Login secondDevice = loginExisting("logout_all_user");
        mvc.perform(post("/api/v1/auth/logout-all")
                        .header("Authorization", bearer(all.accessToken())))
                .andExpect(status().isOk());
        assertRefreshRejected(all.refreshToken());
        assertRefreshRejected(secondDevice.refreshToken());
    }

    private Login createAndLogin(String username) throws Exception {
        UserEntity user = new UserEntity();
        user.setPublicId(UUID.randomUUID().toString());
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setEmailVerifiedAt(Instant.now());
        user.setPasswordHash(passwords.encode("StrongPass123!"));
        user.setStatus("ACTIVE");
        user.setTimezone("Asia/Shanghai");
        user.setLoginFailedCount(0);
        users.insert(user);
        users.addRole(user.getId(), 1L);

        return loginExisting(username);
    }

    private Login loginExisting(String username) throws Exception {
        String response = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"" + username + "\",\"password\":\"StrongPass123!\",\"deviceId\":\"test\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode data = json.readTree(response).path("data");
        UserEntity user = users.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getUsername, username));
        UserEntity persisted = users.selectById(user.getId());
        return new Login(user.getId(), user.getPublicId(), data.path("accessToken").asText(),
                data.path("refreshToken").asText(), persisted.getVersion());
    }

    private void assertRefreshRejected(String refreshToken) throws Exception {
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\",\"deviceId\":\"test\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_TOKEN_EXPIRED"));
    }

    private void grantAdmin(long userId) {
        jdbc.update("INSERT INTO sys_permission(id,code,name,resource_type) "
                + "SELECT 91,'user:read','user read','USER' WHERE NOT EXISTS "
                + "(SELECT 1 FROM sys_permission WHERE code='user:read')");
        jdbc.update("INSERT INTO sys_permission(id,code,name,resource_type) "
                + "SELECT 92,'user:status:write','user status write','USER' WHERE NOT EXISTS "
                + "(SELECT 1 FROM sys_permission WHERE code='user:status:write')");
        jdbc.update("INSERT INTO sys_role_permission(role_id,permission_id) "
                + "SELECT 2,id FROM sys_permission p WHERE p.code IN ('user:read','user:status:write') "
                + "AND NOT EXISTS (SELECT 1 FROM sys_role_permission rp WHERE rp.role_id=2 AND rp.permission_id=p.id)");
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,2)", userId);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record Login(long userId, String publicId, String accessToken, String refreshToken, int version) {}
}
