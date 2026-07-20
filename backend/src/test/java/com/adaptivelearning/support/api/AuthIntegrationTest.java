package com.adaptivelearning.support.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthIntegrationTest {
 @Autowired MockMvc mvc;@Autowired ObjectMapper json;
 @Test void registerThenReadCurrentUserAndRejectAnonymous()throws Exception{
  String body="{\"username\":\"student01\",\"email\":\"student01@example.com\",\"password\":\"StrongPass123!\",\"deviceId\":\"test\"}";
  String response=mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andExpect(jsonPath("$.success").value(true)).andReturn().getResponse().getContentAsString();
  JsonNode root=json.readTree(response);String token=root.path("data").path("accessToken").asText();assertThat(token).isNotBlank();
  mvc.perform(get("/api/v1/users/me").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value("student01")).andExpect(jsonPath("$.requestId").isNotEmpty());
  mvc.perform(get("/api/v1/learning-directions").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].name").value("计算机科学"));
  mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("AUTH_UNAUTHENTICATED"));
 }
}
