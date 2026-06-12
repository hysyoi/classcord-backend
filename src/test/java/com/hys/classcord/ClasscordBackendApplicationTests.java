package com.hys.classcord;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test") // 載入 application-test.yml
class ClasscordBackendApplicationTests {

    @Test
    void contextLoads() {}
}
