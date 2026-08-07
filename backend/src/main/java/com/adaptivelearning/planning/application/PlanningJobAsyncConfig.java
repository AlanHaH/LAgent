package com.adaptivelearning.planning.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 规划作业异步执行器：POST 提交后立即返回，模型生成在后台线程执行，
 * 前端通过 GET /planning-jobs/{jobId} 轮询结果。与画像访谈、知识问答共用一个模式。
 */
@Configuration
public class PlanningJobAsyncConfig {
    @Bean(name = "planningJobExecutor")
    public Executor planningJobExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("planning-job-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        return executor;
    }
}
