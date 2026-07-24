package com.hys.classcord.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;

@Configuration
public class SchedulerConfig {

    /** 專為 Java 21 虛擬執行緒 (Virtual Threads) 設計的全域 STOMP/Task 心跳定時排程器 Bean */
    @Bean
    public TaskScheduler wssHeartbeatTaskScheduler() {
        SimpleAsyncTaskScheduler scheduler = new SimpleAsyncTaskScheduler();
        scheduler.setVirtualThreads(true);
        scheduler.setThreadNamePrefix("wss-heartbeat-vthread-");
        return scheduler;
    }
}
