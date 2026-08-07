package com.adaptivelearning.knowledgebase.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class DocumentProcessingAsyncConfig {
    @Bean(name="documentProcessingExecutor")
    public Executor documentProcessingExecutor(){
        ThreadPoolTaskExecutor executor=new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("document-processing-");
        executor.setCorePoolSize(2);executor.setMaxPoolSize(4);executor.setQueueCapacity(100);
        executor.setWaitForTasksToCompleteOnShutdown(true);executor.setAwaitTerminationSeconds(10);
        executor.initialize();return executor;
    }
}
