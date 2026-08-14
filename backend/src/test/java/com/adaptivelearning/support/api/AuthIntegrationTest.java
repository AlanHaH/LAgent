package com.adaptivelearning.support.api;

import com.adaptivelearning.support.application.EmailVerificationPurpose;
import com.adaptivelearning.support.application.VerificationMailService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthIntegrationTest {
 @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;
 @MockBean VerificationMailService mailService;
 @Test void allowsLocalhostAndLoopbackFrontendOrigins()throws Exception{
  for(String origin:new String[]{"http://localhost:5300","http://127.0.0.1:5300"}){
   mvc.perform(options("/api/v1/auth/email-verification-codes")
           .header("Origin",origin)
           .header("Access-Control-Request-Method","POST")
           .header("Access-Control-Request-Headers","content-type,x-request-id"))
           .andExpect(status().isOk())
           .andExpect(header().string("Access-Control-Allow-Origin",origin));
  }
 }
 @Test void verifyEmailRegisterResetPasswordAndRejectAnonymous()throws Exception{
  String email="student01@example.com";
  mvc.perform(post("/api/v1/auth/email-verification-codes").contentType(MediaType.APPLICATION_JSON)
          .content("{\"email\":\""+email+"\",\"purpose\":\"REGISTER\"}"))
          .andExpect(status().isOk()).andExpect(jsonPath("$.data.expiresInSeconds").value(600));
  var registerCode=org.mockito.ArgumentCaptor.forClass(String.class);
  verify(mailService).sendVerificationCode(eq(email),eq(EmailVerificationPurpose.REGISTER),registerCode.capture(),any());
  assertThat(registerCode.getValue()).matches("\\d{6}");

  String body="{\"username\":\"student01\",\"email\":\""+email+"\",\"password\":\"StrongPass123!\",\"verificationCode\":\""+registerCode.getValue()+"\",\"deviceId\":\"test\"}";
  String response=mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andExpect(jsonPath("$.success").value(true)).andReturn().getResponse().getContentAsString();
  JsonNode root=json.readTree(response);String token=root.path("data").path("accessToken").asText();assertThat(token).isNotBlank();
  mvc.perform(get("/api/v1/users/me").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value("student01")).andExpect(jsonPath("$.data.emailVerified").value(true)).andExpect(jsonPath("$.requestId").isNotEmpty());
  mvc.perform(get("/api/v1/learning-directions").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].name").value("计算机科学"));
  mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("AUTH_UNAUTHENTICATED"));

  String newEmail="student02@example.com";
  mvc.perform(post("/api/v1/users/me/email-verification-code").header("Authorization","Bearer "+token)
          .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+newEmail+"\"}"))
          .andExpect(status().isOk());
  var changeCode=org.mockito.ArgumentCaptor.forClass(String.class);
  verify(mailService).sendVerificationCode(eq(newEmail),eq(EmailVerificationPurpose.CHANGE_EMAIL),changeCode.capture(),any());
  int userVersion=root.path("data").path("user").path("version").asInt();
  mvc.perform(patch("/api/v1/users/me").header("Authorization","Bearer "+token)
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"email\":\""+newEmail+"\",\"emailVerificationCode\":\""+changeCode.getValue()+"\",\"version\":"+userVersion+"}"))
          .andExpect(status().isOk()).andExpect(jsonPath("$.data.email").value(newEmail))
          .andExpect(jsonPath("$.data.emailVerified").value(true));
  email=newEmail;

  String unknownEmail="missing@example.com";
  mvc.perform(post("/api/v1/auth/email-verification-codes").contentType(MediaType.APPLICATION_JSON)
          .content("{\"email\":\""+unknownEmail+"\",\"purpose\":\"PASSWORD_RESET\"}"))
          .andExpect(status().isOk()).andExpect(jsonPath("$.data.expiresInSeconds").value(600));
  verify(mailService,never()).sendVerificationCode(eq(unknownEmail),any(),any(),any());

  mvc.perform(post("/api/v1/auth/email-verification-codes").contentType(MediaType.APPLICATION_JSON)
          .content("{\"email\":\""+email+"\",\"purpose\":\"PASSWORD_RESET\"}"))
          .andExpect(status().isOk());
  var resetCode=org.mockito.ArgumentCaptor.forClass(String.class);
  verify(mailService).sendVerificationCode(eq(email),eq(EmailVerificationPurpose.PASSWORD_RESET),resetCode.capture(),any());
  String latestCode=resetCode.getValue();
  mvc.perform(post("/api/v1/auth/password-reset").contentType(MediaType.APPLICATION_JSON)
          .content("{\"email\":\""+email+"\",\"verificationCode\":\""+latestCode+"\",\"newPassword\":\"NewStrongPass456!\"}"))
          .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
  mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
          .content("{\"login\":\""+email+"\",\"password\":\"StrongPass123!\"}"))
          .andExpect(status().isUnauthorized());
  mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
           .content("{\"login\":\""+email+"\",\"password\":\"NewStrongPass456!\"}"))
           .andExpect(status().isOk());
 }

 @Test void persistsFailedLoginsAndLocksAfterFiveAttempts()throws Exception{
  String email="lock-user@example.com";
  mvc.perform(post("/api/v1/auth/email-verification-codes").contentType(MediaType.APPLICATION_JSON)
          .content("{\"email\":\""+email+"\",\"purpose\":\"REGISTER\"}"))
          .andExpect(status().isOk());
  var code=org.mockito.ArgumentCaptor.forClass(String.class);
  verify(mailService).sendVerificationCode(eq(email),eq(EmailVerificationPurpose.REGISTER),code.capture(),any());
  String body="{\"username\":\"lock_user\",\"email\":\""+email+"\",\"password\":\"StrongPass123!\",\"verificationCode\":\""+code.getValue()+"\",\"deviceId\":\"test\"}";
  mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isCreated());

  for(int attempt=1;attempt<=4;attempt++){
   mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
           .content("{\"login\":\"lock_user\",\"password\":\"wrong-password\"}"))
           .andExpect(status().isUnauthorized())
           .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIALS"));
   Integer failures=jdbc.queryForObject("SELECT login_failed_count FROM sys_user WHERE username='lock_user'",Integer.class);
   assertThat(failures).isEqualTo(attempt);
  }
  mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
          .content("{\"login\":\"lock_user\",\"password\":\"wrong-password\"}"))
          .andExpect(status().isUnauthorized());
  Timestamp lockedUntil=jdbc.queryForObject("SELECT locked_until FROM sys_user WHERE username='lock_user'",Timestamp.class);
  assertThat(lockedUntil).isNotNull();
  assertThat(lockedUntil.toInstant()).isAfter(Instant.now());

  mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
          .content("{\"login\":\"lock_user\",\"password\":\"StrongPass123!\"}"))
          .andExpect(status().isLocked())
          .andExpect(jsonPath("$.error.code").value("AUTH_ACCOUNT_LOCKED"));

  jdbc.update("UPDATE sys_user SET locked_until=?,login_failed_count=3 WHERE username='lock_user'",
          Timestamp.from(Instant.now().minusSeconds(1)));
  mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
          .content("{\"login\":\"lock_user\",\"password\":\"StrongPass123!\"}"))
          .andExpect(status().isOk());
  Integer failures=jdbc.queryForObject("SELECT login_failed_count FROM sys_user WHERE username='lock_user'",Integer.class);
  Timestamp clearedLock=jdbc.queryForObject("SELECT locked_until FROM sys_user WHERE username='lock_user'",Timestamp.class);
  assertThat(failures).isZero();
  assertThat(clearedLock).isNull();
 }
}
