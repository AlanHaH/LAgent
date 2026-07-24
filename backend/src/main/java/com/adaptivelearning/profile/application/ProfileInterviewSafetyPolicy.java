package com.adaptivelearning.profile.application;

import com.adaptivelearning.shared.exception.BusinessException;
import com.adaptivelearning.shared.exception.ErrorCode;

import java.util.Locale;
import java.util.regex.Pattern;

final class ProfileInterviewSafetyPolicy {
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(api[_ -]?key|access[_ -]?token|password|密码|授权码)\\s*[:=：]\\s*\\S{4,}");
    private static final String[] PROMPT_ATTACKS = {
            "ignore previous instructions", "ignore system prompt", "reveal system prompt",
            "忽略之前的指令", "忽略以上指令", "忽略系统提示", "输出系统提示", "泄露系统提示"
    };

    private ProfileInterviewSafetyPolicy() {}

    static void validate(String content) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "请先说说你的学习目标或当前情况");
        }
        if (content.length() > 2000) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "单条访谈消息不能超过 2000 字");
        }
        if (SECRET.matcher(content).find()) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,
                    "消息中可能包含密码、授权码或密钥，请删除敏感信息后再发送");
        }
        String normalized = content.toLowerCase(Locale.ROOT);
        for (String attack : PROMPT_ATTACKS) {
            if (normalized.contains(attack)) {
                throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,
                        "消息包含可能改变 AI 系统规则的内容，请只描述学习情况");
            }
        }
    }
}
