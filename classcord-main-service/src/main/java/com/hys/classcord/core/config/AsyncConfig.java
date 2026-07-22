package com.hys.classcord.core.config;

import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;

@Slf4j
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (throwable, method, obj) -> {
            log.error(
                    "[AsyncConfig] 非同步任務執行發生異常。方法: {}, 參數: {}, 原因: {}",
                    method.getName(),
                    Arrays.toString(obj),
                    throwable.getMessage(),
                    throwable);
            // 此處未來可以串接其他服務
        };
    }
}
