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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CatalogIntegrityIntegrationTest {
    private static final long BIG_ID_BASE = 9_007_199_254_741_000L;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserMapper users;
    @Autowired PasswordEncoder passwords;

    @Test
    void snowflakeIdsRoundTripFromAdminCatalogThroughProfileAndSelfAssessment() throws Exception {
        Login admin = createAndLogin("catalog_big_admin", true);
        Login student = createAndLogin("catalog_big_student", false);

        JsonNode direction = adminPost(admin, "/api/v1/admin/learning-directions", """
                {"parentId":null,"code":"BIG_ID_DIRECTION","name":"Big ID direction",
                 "status":"ACTIVE","sortNo":1,"version":null}
                """);
        String directionId = direction.path("id").asText();
        assertThat(direction.path("id").isTextual()).isTrue();
        assertThat(Long.parseLong(directionId)).isGreaterThan(9_007_199_254_740_991L);

        JsonNode point = adminPost(admin, "/api/v1/admin/knowledge-points", """
                {"id":null,"directionId":"%s","parentId":null,"code":"BIG_ID_POINT",
                 "name":"Big ID point","level":1,"defaultWeight":1,"status":"ACTIVE","version":null}
                """.formatted(directionId));
        String pointId = point.path("id").asText();
        assertThat(point.path("id").isTextual()).isTrue();
        assertThat(Long.parseLong(pointId)).isGreaterThan(9_007_199_254_740_991L);

        mvc.perform(get("/api/v1/learning-directions").header("Authorization", bearer(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == '%s')]".formatted(directionId)).exists());
        mvc.perform(get("/api/v1/knowledge-points").param("directionId", directionId)
                        .header("Authorization", bearer(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(pointId));

        mvc.perform(put("/api/v1/profiles/me").header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON).content(profileBody(directionId, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.directions[0].directionId").value(directionId));

        mvc.perform(post("/api/v1/profiles/me/self-assessments").header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"knowledgePointId\":\"" + pointId + "\",\"level\":3}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.knowledgePointId").value(pointId));

        assertThat(jdbc.queryForObject("SELECT direction_id FROM user_profile_direction WHERE profile_id=(SELECT id FROM user_profile WHERE user_id=?)", Long.class, student.userId()))
                .isEqualTo(Long.parseLong(directionId));
        assertThat(jdbc.queryForObject("SELECT knowledge_point_id FROM self_assessment WHERE user_id=?", Long.class, student.userId()))
                .isEqualTo(Long.parseLong(pointId));
    }

    @Test
    void learnerOnlySeesActiveCatalogWhileAdminSeesAllStatuses() throws Exception {
        Login admin = createAndLogin("catalog_status_admin", true);
        Login student = createAndLogin("catalog_status_student", false);
        long activeDirection = nextId(10), draftDirection = nextId(11), disabledDirection = nextId(12);
        insertDirection(activeDirection, null, "ACTIVE_STATUS", "Active", "ACTIVE", null);
        insertDirection(draftDirection, null, "DRAFT_STATUS", "Draft", "DRAFT", null);
        insertDirection(disabledDirection, null, "DISABLED_STATUS", "Disabled", "DISABLED", null);
        insertPoint(nextId(20), activeDirection, null, "ACTIVE_POINT", "Active point", "ACTIVE", null);
        insertPoint(nextId(21), activeDirection, null, "DRAFT_POINT", "Draft point", "DRAFT", null);
        insertPoint(nextId(22), disabledDirection, null, "POINT_DISABLED_DIRECTION", "Hidden point", "ACTIVE", null);

        String learnerDirections = mvc.perform(get("/api/v1/learning-directions")
                        .header("Authorization", bearer(student))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(learnerDirections).contains(String.valueOf(activeDirection))
                .doesNotContain(String.valueOf(draftDirection), String.valueOf(disabledDirection));

        String learnerPoints = mvc.perform(get("/api/v1/knowledge-points")
                        .header("Authorization", bearer(student))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(learnerPoints).contains("ACTIVE_POINT")
                .doesNotContain("DRAFT_POINT", "POINT_DISABLED_DIRECTION");

        String adminDirections = mvc.perform(get("/api/v1/admin/learning-directions")
                        .header("Authorization", bearer(admin))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(adminDirections).contains("ACTIVE_STATUS", "DRAFT_STATUS", "DISABLED_STATUS");
        String adminPoints = mvc.perform(get("/api/v1/admin/knowledge-points")
                        .header("Authorization", bearer(admin))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(adminPoints).contains("ACTIVE_POINT", "DRAFT_POINT", "POINT_DISABLED_DIRECTION");
    }

    @Test
    void studentCannotAccessAdminCatalog() throws Exception {
        Login student = createAndLogin("catalog_forbidden_student", false);
        mvc.perform(get("/api/v1/admin/learning-directions").header("Authorization", bearer(student)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AUTH_FORBIDDEN"));
    }

    @Test
    void directionRejectsMissingDeletedAndCyclicParents() throws Exception {
        Login admin = createAndLogin("direction_integrity_admin", true);
        adminPostExpect(admin, "/api/v1/admin/learning-directions", """
                {"parentId":"%d","code":"MISSING_PARENT","name":"Missing parent",
                 "status":"ACTIVE","sortNo":1,"version":null}
                """.formatted(nextId(31)), 400);

        long deletedParent = nextId(32);
        insertDirection(deletedParent, null, "DELETED_PARENT", "Deleted parent", "ACTIVE", Instant.now());
        adminPostExpect(admin, "/api/v1/admin/learning-directions", """
                {"parentId":"%d","code":"DELETED_PARENT_CHILD","name":"Deleted child",
                 "status":"ACTIVE","sortNo":1,"version":null}
                """.formatted(deletedParent), 400);

        JsonNode a = createDirection(admin, "DIR_CYCLE_A", null);
        JsonNode b = createDirection(admin, "DIR_CYCLE_B", a.path("id").asText());
        adminPostExpect(admin, "/api/v1/admin/learning-directions", directionUpdate(a, b.path("id").asText()), 409);

        JsonNode x = createDirection(admin, "DIR_MULTI_X", null);
        JsonNode y = createDirection(admin, "DIR_MULTI_Y", x.path("id").asText());
        JsonNode z = createDirection(admin, "DIR_MULTI_Z", y.path("id").asText());
        adminPostExpect(admin, "/api/v1/admin/learning-directions", directionUpdate(x, z.path("id").asText()), 409);
    }

    @Test
    void knowledgeHierarchyAndDirectionMigrationPreserveAllRelationships() throws Exception {
        Login admin = createAndLogin("knowledge_integrity_admin", true);
        JsonNode d1 = createDirection(admin, "KP_DIR_ONE", null);
        JsonNode d2 = createDirection(admin, "KP_DIR_TWO", null);

        adminPostExpect(admin, "/api/v1/admin/knowledge-points", knowledgeBody(
                null, d1.path("id").asText(), String.valueOf(nextId(40)), "KP_MISSING_PARENT", null), 400);

        JsonNode parent = createPoint(admin, d1, "KP_PARENT", null);
        JsonNode child = createPoint(admin, d1, "KP_CHILD", parent.path("id").asText());
        adminPostExpect(admin, "/api/v1/admin/knowledge-points", knowledgeBody(
                parent.path("id").asText(), d1.path("id").asText(), child.path("id").asText(),
                "KP_PARENT", parent.path("version").asInt()), 409);

        adminPostExpect(admin, "/api/v1/admin/knowledge-points", knowledgeBody(
                parent.path("id").asText(), d2.path("id").asText(), null,
                "KP_PARENT", parent.path("version").asInt()), 400);

        JsonNode predecessor = createPoint(admin, d1, "KP_PREDECESSOR", null);
        JsonNode successor = createPoint(admin, d1, "KP_SUCCESSOR", null);
        adminPost(admin, "/api/v1/admin/knowledge-dependencies", """
                {"predecessorId":"%s","successorId":"%s"}
                """.formatted(predecessor.path("id").asText(), successor.path("id").asText()));
        adminPostExpect(admin, "/api/v1/admin/knowledge-points", knowledgeBody(
                predecessor.path("id").asText(), d2.path("id").asText(), null,
                "KP_PREDECESSOR", predecessor.path("version").asInt()), 400);

        JsonNode foreign = createPoint(admin, d2, "KP_FOREIGN", null);
        adminPostExpect(admin, "/api/v1/admin/knowledge-dependencies", """
                {"predecessorId":"%s","successorId":"%s"}
                """.formatted(successor.path("id").asText(), foreign.path("id").asText()), 400);
    }

    @Test
    void selfAssessmentRequiresUsersActivePublicProfileDirectionAndRejectsCustomDirection() throws Exception {
        Login publicStudent = createAndLogin("self_assessment_public", false);
        Login customStudent = createAndLogin("self_assessment_custom", false);
        Login deletedDirectionStudent = createAndLogin("self_assessment_deleted", false);
        long ownedDirection = nextId(50), otherDirection = nextId(51);
        long ownedPoint = nextId(52), otherPoint = nextId(53), deletedDirection = nextId(54);
        insertDirection(ownedDirection, null, "SELF_OWNED_DIR", "Owned direction", "ACTIVE", null);
        insertDirection(otherDirection, null, "SELF_OTHER_DIR", "Other direction", "ACTIVE", null);
        insertPoint(ownedPoint, ownedDirection, null, "SELF_OWNED_POINT", "Owned point", "ACTIVE", null);
        insertPoint(otherPoint, otherDirection, null, "SELF_OTHER_POINT", "Other point", "ACTIVE", null);
        insertDirection(deletedDirection, null, "SELF_DELETED_DIR", "Deleted direction", "ACTIVE", Instant.now());

        mvc.perform(put("/api/v1/profiles/me").header("Authorization", bearer(deletedDirectionStudent))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profileBody(String.valueOf(deletedDirection), null)))
                .andExpect(status().isNotFound());

        mvc.perform(put("/api/v1/profiles/me").header("Authorization", bearer(publicStudent))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profileBody(String.valueOf(ownedDirection), null)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/profiles/me/self-assessments").header("Authorization", bearer(publicStudent))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"knowledgePointId\":\"" + ownedPoint + "\",\"level\":4}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/profiles/me/self-assessments").header("Authorization", bearer(publicStudent))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"knowledgePointId\":\"" + otherPoint + "\",\"level\":4}"))
                .andExpect(status().isNotFound());

        mvc.perform(put("/api/v1/profiles/me").header("Authorization", bearer(customStudent))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profileBody(null, "Custom-only direction")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/profiles/me/self-assessments").header("Authorization", bearer(customStudent))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"knowledgePointId\":\"" + ownedPoint + "\",\"level\":4}"))
                .andExpect(status().isNotFound());
    }

    private JsonNode createDirection(Login admin, String code, String parentId) throws Exception {
        return adminPost(admin, "/api/v1/admin/learning-directions", """
                {"parentId":%s,"code":"%s","name":"%s","status":"ACTIVE","sortNo":1,"version":null}
                """.formatted(parentId == null ? "null" : "\"" + parentId + "\"", code, code));
    }

    private JsonNode createPoint(Login admin, JsonNode direction, String code, String parentId) throws Exception {
        return adminPost(admin, "/api/v1/admin/knowledge-points", knowledgeBody(
                null, direction.path("id").asText(), parentId, code, null));
    }

    private String directionUpdate(JsonNode direction, String parentId) {
        return """
                {"id":"%s","parentId":"%s","code":"%s","name":"%s",
                 "status":"ACTIVE","sortNo":1,"version":%d}
                """.formatted(direction.path("id").asText(), parentId, direction.path("code").asText(),
                direction.path("name").asText(), direction.path("version").asInt());
    }

    private String knowledgeBody(String id, String directionId, String parentId, String code, Integer version) {
        return """
                {"id":%s,"directionId":"%s","parentId":%s,"code":"%s","name":"%s",
                 "level":1,"defaultWeight":1,"status":"ACTIVE","version":%s}
                """.formatted(id == null ? "null" : "\"" + id + "\"", directionId,
                parentId == null ? "null" : "\"" + parentId + "\"", code, code,
                version == null ? "null" : version);
    }

    private String profileBody(String directionId, String customDirection) {
        String direction = directionId == null
                ? "{\"directionId\":null,\"customDirection\":\"" + customDirection + "\",\"currentStage\":\"BEGINNER\",\"primary\":true}"
                : "{\"directionId\":\"" + directionId + "\",\"customDirection\":null,\"currentStage\":\"BEGINNER\",\"primary\":true}";
        return """
                {"timezone":"Asia/Shanghai","weekStart":1,"planPeriodDays":30,
                 "planStartDate":"2026-08-01","planEndDate":"2026-08-30","backgroundText":"test",
                 "directions":[%s],"version":null}
                """.formatted(direction);
    }

    private JsonNode adminPost(Login admin, String url, String body) throws Exception {
        String response = mvc.perform(post(url).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("data");
    }

    private void adminPostExpect(Login admin, String url, String body, int expectedStatus) throws Exception {
        mvc.perform(post(url).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus));
    }

    private Login createAndLogin(String prefix, boolean admin) throws Exception {
        String username = prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
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
        users.addRole(user.getId(), admin ? 2L : 1L);
        if (admin) grantCatalogPermissions();

        String response = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"" + username + "\",\"password\":\"StrongPass123!\",\"deviceId\":\"m02\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Login(user.getId(), json.readTree(response).path("data").path("accessToken").asText());
    }

    private void grantCatalogPermissions() {
        jdbc.update("INSERT INTO sys_permission(id,code,name,resource_type) SELECT 940,'direction:write','direction write','DIRECTION' WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code='direction:write')");
        jdbc.update("INSERT INTO sys_permission(id,code,name,resource_type) SELECT 941,'knowledge-point:write','knowledge write','KNOWLEDGE_POINT' WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code='knowledge-point:write')");
        jdbc.update("INSERT INTO sys_role_permission(role_id,permission_id) SELECT 2,id FROM sys_permission p WHERE p.code IN ('direction:write','knowledge-point:write') AND NOT EXISTS (SELECT 1 FROM sys_role_permission rp WHERE rp.role_id=2 AND rp.permission_id=p.id)");
    }

    private void insertDirection(long id, Long parentId, String code, String name, String status, Instant deletedAt) {
        jdbc.update("INSERT INTO learning_direction(id,parent_id,code,name,status,sort_no,version,deleted_at) VALUES(?,?,?,?,?,1,0,?)",
                id, parentId, code + id, name, status, deletedAt);
    }

    private void insertPoint(long id, long directionId, Long parentId, String code, String name,
                             String status, Instant deletedAt) {
        jdbc.update("INSERT INTO knowledge_point(id,direction_id,parent_id,code,name,level,default_weight,status,version,deleted_at) VALUES(?,?,?,?,?,1,1,?,0,?)",
                id, directionId, parentId, code + id, name, status, deletedAt);
    }

    private long nextId(int offset) {
        return BIG_ID_BASE + offset;
    }

    private String bearer(Login login) {
        return "Bearer " + login.accessToken();
    }

    private record Login(long userId, String accessToken) {}
}
