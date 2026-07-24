package com.adaptivelearning.support.application;

import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class EmailVerificationServiceTest {
    @Test
    void consumesAValidCodeOnlyOnce() {
        VerificationMailService mail = mock(VerificationMailService.class);
        EmailVerificationService service = service(mail, 5);

        service.sendCode("Student@Example.com", EmailVerificationPurpose.REGISTER);
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(mail).sendVerificationCode(eq("student@example.com"),
                eq(EmailVerificationPurpose.REGISTER), code.capture(), any(Duration.class));

        assertThat(code.getValue()).matches("\\d{6}");
        assertThatCode(() -> service.verifyAndConsume("student@example.com",
                EmailVerificationPurpose.REGISTER, code.getValue())).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.verifyAndConsume("student@example.com",
                EmailVerificationPurpose.REGISTER, code.getValue()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(ErrorCode.AUTH_VERIFICATION_CODE_INVALID));
    }

    @Test
    void invalidatesTheCodeAfterMaximumFailedAttempts() {
        VerificationMailService mail = mock(VerificationMailService.class);
        EmailVerificationService service = service(mail, 2);

        service.sendCode("student@example.com", EmailVerificationPurpose.PASSWORD_RESET);
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(mail).sendVerificationCode(eq("student@example.com"),
                eq(EmailVerificationPurpose.PASSWORD_RESET), code.capture(), any(Duration.class));

        String firstWrongCode = code.getValue().equals("111111") ? "333333" : "111111";
        String secondWrongCode = code.getValue().equals("222222") ? "444444" : "222222";
        assertThatThrownBy(() -> service.verifyAndConsume("student@example.com",
                EmailVerificationPurpose.PASSWORD_RESET, firstWrongCode));
        assertThatThrownBy(() -> service.verifyAndConsume("student@example.com",
                EmailVerificationPurpose.PASSWORD_RESET, secondWrongCode))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getMessage()).contains("错误次数过多"));
        assertThatThrownBy(() -> service.verifyAndConsume("student@example.com",
                EmailVerificationPurpose.PASSWORD_RESET, code.getValue()));
    }

    private EmailVerificationService service(VerificationMailService mail, int maxAttempts) {
        return new EmailVerificationService(null, mail, new HashingService(), "memory",
                10, 60, maxAttempts, "test-verification-pepper");
    }
}
