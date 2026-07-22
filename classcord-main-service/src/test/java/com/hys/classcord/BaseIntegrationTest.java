package com.hys.classcord;

import com.hys.classcord.auth.service.MailService;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@SpringBootTest(classes = ClasscordMainApplication.class, properties = "app.turnstile.enabled=false")
@ActiveProfiles("test") // 載入 application-test.yml 的測試環境設定
@AutoConfigureMockMvc // 自動配置 MockMvc，用來模擬 HTTP 請求
@Transactional // 測試結束後自動 Rollback 資料庫，保持測試資料庫乾淨
public abstract class BaseIntegrationTest {

    @MockBean protected S3Client s3Client;
    @MockBean protected S3Presigner s3Presigner;
    // 用 MockBean 擋掉郵件發送，避免測試時真的連線 Mailpit 或外部伺服器
    @MockBean protected MailService mailService;
}
