package com.adaptivelearning.support.application;

import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Properties;

@Slf4j
@Service
public class VerificationMailService {
    private final JavaMailSenderImpl mailSender;
    private final String from;
    private final boolean configured;

    public VerificationMailService(
            @Value("${app.mail.host:}") String host,
            @Value("${app.mail.port:587}") int port,
            @Value("${app.mail.username:}") String username,
            @Value("${app.mail.password:}") String password,
            @Value("${app.mail.from:}") String from,
            @Value("${app.mail.auth-enabled:true}") boolean authEnabled,
            @Value("${app.mail.starttls-enabled:true}") boolean startTlsEnabled,
            @Value("${app.mail.ssl-enabled:false}") boolean sslEnabled,
            @Value("${app.mail.connection-timeout:PT10S}") Duration connectionTimeout,
            @Value("${app.mail.write-timeout:PT10S}") Duration writeTimeout,
            @Value("${app.mail.read-timeout:PT10S}") Duration readTimeout) {
        this.mailSender = new JavaMailSenderImpl();
        this.mailSender.setHost(host);
        this.mailSender.setPort(port);
        this.mailSender.setUsername(username);
        this.mailSender.setPassword(password);
        this.from = StringUtils.hasText(from) ? from : username;
        this.configured = StringUtils.hasText(host) && StringUtils.hasText(this.from);

        Properties properties = this.mailSender.getJavaMailProperties();
        properties.put("mail.smtp.auth", Boolean.toString(authEnabled));
        properties.put("mail.smtp.starttls.enable", Boolean.toString(startTlsEnabled));
        properties.put("mail.smtp.ssl.enable", Boolean.toString(sslEnabled));
        properties.put("mail.smtp.connectiontimeout", Long.toString(connectionTimeout.toMillis()));
        properties.put("mail.smtp.writetimeout", Long.toString(writeTimeout.toMillis()));
        properties.put("mail.smtp.timeout", Long.toString(readTimeout.toMillis()));
    }

    public void sendVerificationCode(String recipient, EmailVerificationPurpose purpose,
                                     String code, Duration validity) {
        if (!configured) {
            throw unavailable("邮件服务尚未配置，请联系管理员");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(recipient);
        message.setSubject(subject(purpose));
        message.setText(body(purpose, code, validity));
        try {
            mailSender.send(message);
        } catch (MailException exception) {
            log.warn("Verification email delivery failed: purpose={}", purpose, exception);
            throw unavailable("验证码邮件发送失败，请稍后重试");
        }
    }

    private String subject(EmailVerificationPurpose purpose) {
        return switch (purpose) {
            case REGISTER -> "知序｜注册邮箱验证码";
            case PASSWORD_RESET -> "知序｜重置密码验证码";
            case CHANGE_EMAIL -> "知序｜更换邮箱验证码";
        };
    }

    private String body(EmailVerificationPurpose purpose, String code, Duration validity) {
        String action = switch (purpose) {
            case REGISTER -> "注册知序账户";
            case PASSWORD_RESET -> "重置知序账户密码";
            case CHANGE_EMAIL -> "更换知序账户邮箱";
        };
        return "你正在" + action + "。\n\n验证码：" + code
                + "\n\n验证码在 " + validity.toMinutes() + " 分钟内有效，请勿转发给他人。"
                + "\n如果不是你本人操作，请忽略此邮件。";
    }

    private BusinessException unavailable(String message) {
        return new BusinessException(ErrorCode.SERVICE_TEMPORARILY_UNAVAILABLE, message);
    }
}
