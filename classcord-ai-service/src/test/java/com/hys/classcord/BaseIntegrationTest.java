package com.hys.classcord;

import com.hys.classcord.ai.ClasscordAiApplication;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@SpringBootTest(classes = ClasscordAiApplication.class, properties = "app.turnstile.enabled=false")
@ActiveProfiles("test") // 載入 application-test.yml 的測試環境設定
@AutoConfigureMockMvc // 自動配置 MockMvc，用來模擬 HTTP 請求
@Transactional // 測試結束後自動 Rollback 資料庫，保持測試資料庫乾淨
public abstract class BaseIntegrationTest {

    @MockBean protected S3Client s3Client;
    @MockBean protected S3Presigner s3Presigner;

    @Autowired private RedisConnectionFactory redisConnectionFactory;

    // Redis 不在 @Transactional 的 rollback 範圍內，測試結束後手動清空。
    @AfterEach
    void flushRedis() {
        try (var connection = redisConnectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }
}
