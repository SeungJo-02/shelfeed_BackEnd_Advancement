package com.shelfeed.backend.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * @EnableAsync(BackendApplication)가 사용할 바운디드 Executor.
 * 명시적 Executor가 없으면 Spring이 무제한 SimpleAsyncTaskExecutor로 폴백하므로,
 * 메일 등 fire-and-forget 외부 호출용으로 상한이 있는 풀을 제공한다.
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "mailExecutor")
    public ThreadPoolTaskExecutor mailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("mail-async-");
        // 큐가 가득 차면 호출 스레드에서 직접 실행 — 작업 유실 방지
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        executor.initialize();
        return executor;
    }
}
