package com.adaptivelearning.support.api;

import com.adaptivelearning.shared.api.ApiResponse;
import com.adaptivelearning.support.application.AdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 供 Python AI 服务内部拉取的端点。仅限 Java 后端与 Python AI 服务之间的信任通道调用，
 * 鉴权由 {@code InternalTokenFilter} 校验 X-Internal-Token 头完成。
 * 返回当前 ACTIVE 的提示词模板，作为 Python 运行时系统提示词的唯一权威来源。
 */
@RestController
@RequestMapping("/internal/v1/prompt-templates")
@RequiredArgsConstructor
public class InternalPromptController {
    private final AdminService service;

    @GetMapping
    public ApiResponse<List<AdminService.PromptTemplateDto>> active() {
        return ApiResponse.ok(service.activePromptTemplates());
    }
}
