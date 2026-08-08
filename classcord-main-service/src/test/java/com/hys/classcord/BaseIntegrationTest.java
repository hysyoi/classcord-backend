package com.hys.classcord;

import com.hys.classcord.auth.service.MailService;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@SpringBootTest(
        classes = ClasscordMainApplication.class,
        properties = "app.turnstile.enabled=false")
@ActiveProfiles("test") // 載入 application-test.yml 的測試環境設定
@AutoConfigureMockMvc // 自動配置 MockMvc，用來模擬 HTTP 請求
@Transactional // 測試結束後自動 Rollback 資料庫，保持測試資料庫乾淨
public abstract class BaseIntegrationTest {

    @MockBean protected S3Client s3Client;
    @MockBean protected S3Presigner s3Presigner;
    // 用 MockBean 擋掉郵件發送，避免測試時真的連線 Mailpit 或外部伺服器
    @MockBean protected MailService mailService;

    @Autowired private RedisConnectionFactory redisConnectionFactory;

    // Redis 不在 @Transactional 的 rollback 範圍內，測試結束後手動清空，
    // 避免各測試類別要各自維護一份要清哪些 key prefix 的清單（容易漏寫、互相干擾）。
    // redis-test 是專屬測試的獨立容器，flushDb 不會波及其他環境。
    @AfterEach
    void flushRedis() {
        try (var connection = redisConnectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    // 測試包在假交易裡，結束時只會 rollback、不會真的 commit，
    // 所以依賴「交易 commit 後才觸發」的正式邏輯（例如刪除 Redis token、發送 MQ 訊息）
    // 永遠不會自然執行，需要手動觸發已註冊的 afterCommit 回呼來模擬。
    protected void triggerAfterCommitCallbacks() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
        }
    }
}
