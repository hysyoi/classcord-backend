package com.hys.classcord.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 確認 RestClient.Builder 不會在注入點之間共用。
 *
 * <p>重現正式環境的情境：Spring AI 的 OpenAiApi 先拿到 Builder，在上面設定 baseUrl 與 Authorization: Bearer， 之後才 build 的
 * restClient 不能繼承這些設定。
 */
class WebConfigTest {

    private HttpServer server;
    private final AtomicReference<String> receivedAuthorization = new AtomicReference<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    receivedAuthorization.set(
                            exchange.getRequestHeaders().getFirst("Authorization"));
                    exchange.sendResponseHeaders(200, -1);
                    exchange.close();
                });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    // PollutingConfig 先註冊，它的 bean 會比 WebConfig.restClient 先建立
                    .withUserConfiguration(PollutingConfig.class, WebConfig.class);

    @Test
    void restClientDoesNotInheritSettingsAddedToAnotherBuilder() {
        contextRunner.run(
                context -> {
                    RestClient restClient = context.getBean("restClient", RestClient.class);

                    restClient
                            .get()
                            .uri("http://127.0.0.1:" + server.getAddress().getPort() + "/check")
                            .retrieve()
                            .toBodilessEntity();

                    assertThat(receivedAuthorization.get()).isNull();
                });
    }

    @Test
    void buildersAreNotSharedButConnectionPoolIs() {
        contextRunner.run(
                context -> {
                    assertThat(context.getBean(RestClient.Builder.class))
                            .isNotSameAs(context.getBean(RestClient.Builder.class));
                    assertThat(context.getBeansOfType(HttpComponentsClientHttpRequestFactory.class))
                            .hasSize(1);
                });
    }

    /** 模擬 Spring AI OpenAiApi 對 Builder 的改動 */
    @Configuration(proxyBeanMethods = false)
    static class PollutingConfig {

        @Bean
        RestClient springAiLikeClient(RestClient.Builder builder) {
            return builder.baseUrl("https://generativelanguage.googleapis.com/v1beta/openai/")
                    .defaultHeaders(h -> h.setBearerAuth("fake-key"))
                    .build();
        }
    }
}
