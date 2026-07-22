package com.hys.classcord.gateway.config;

import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.SentinelGatewayFilter;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.BlockRequestHandler;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.exception.SentinelGatewayBlockExceptionHandler;
import jakarta.annotation.PostConstruct;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.ViewResolver;

/** Spring Cloud Gateway 專屬 Sentinel 限流與例外處理配置類 */
@Configuration
public class SentinelGatewayConfig {

    private final List<ViewResolver> viewResolvers;
    private final ServerCodecConfigurer serverCodecConfigurer;

    public SentinelGatewayConfig(
            List<ViewResolver> viewResolvers, ServerCodecConfigurer serverCodecConfigurer) {
        this.viewResolvers = viewResolvers;
        this.serverCodecConfigurer = serverCodecConfigurer;
    }

    /** 配置 Sentinel 網關專屬例外處理器 (最高優先級) 相當於網關層級的 GlobalExceptionHandler，負責捕捉網關層面被 Sentinel 攔截阻擋的請求 */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SentinelGatewayBlockExceptionHandler sentinelGatewayBlockExceptionHandler() {
        return new SentinelGatewayBlockExceptionHandler(viewResolvers, serverCodecConfigurer);
    }

    /** 配置 Sentinel 網關全局過濾器 (最高優先級) 讓所有進入 Gateway 的 HTTP 請求都會先經過 Sentinel 的流量過濾與統計 */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public GlobalFilter sentinelGatewayFilter() {
        return new SentinelGatewayFilter();
    }

    /** 容器初始化完畢後自動執行：載入預設限流規則與註冊自訂被攔截的回應處理器 */
    @PostConstruct
    public void init() {
        initGatewayRules();
        initBlockHandler();
    }

    /** 初始化預設限流規則 (本地兜底預設值) 備註：Nacos 中的 classcord-gateway-sentinel-rules 會在啟動連線後自動覆蓋此處預設值 */
    private void initGatewayRules() {
        Set<GatewayFlowRule> rules = new HashSet<>();
        // 1. 對 ai-service-route 路由進行 QPS 流量限制：預設每秒最多允許 15 次請求
        rules.add(new GatewayFlowRule("ai-service-route").setCount(15).setIntervalSec(1));
        // 2. 對 auth-route 獨立認證路由進行 QPS 流量限制：預設每秒最多允許 50 次請求 (防殭屍網路爆刷登入)
        rules.add(new GatewayFlowRule("auth-route").setCount(50).setIntervalSec(1));
        // 3. 對 main-service-route 路由進行 QPS 流量限制：預設每秒最多允許 300 次請求 (防暴打 DDoS)
        rules.add(new GatewayFlowRule("main-service-route").setCount(300).setIntervalSec(1));
        GatewayRuleManager.loadRules(rules);
    }

    /**
     * 配置自訂被限流時的回應內容 (BlockHandler) 當請求超出 QPS 門檻時，Sentinel 會在最外層大門直接回傳 HTTP 429 Too Many Requests
     * 並精準讀取路由標籤 (Route ID) 回傳前端可辨識的 JSON 物件
     */
    private void initBlockHandler() {
        BlockRequestHandler handler =
                (exchange, t) -> {
                    // 從 Gateway 請求屬性中精準獲取匹配到的路由標籤 (Route)
                    Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
                    String routeId = (route != null) ? route.getId() : "";

                    String code;
                    String message;

                    // 100% 精準判斷是哪個微服務/模組被限流
                    if ("ai-service-route".equals(routeId)) {
                        code = "SYS_AI_RATE_LIMIT";
                        message = "目前同時使用 AI 服務的人數較多，系統正在排隊中，請稍候幾秒再試！";
                    } else if ("auth-route".equals(routeId)) {
                        code = "SYS_AUTH_RATE_LIMIT";
                        message = "目前登入與驗證請求較多，系統正在排隊中，請稍候幾秒再試！";
                    } else {
                        code = "SYS_RATE_LIMIT";
                        message = "目前全站存取人數較多，系統正在排隊中，請稍候幾秒再試！";
                    }

                    String json =
                            String.format(
                                    """
                {
                  "code": "%s",
                  "message": "%s"
                }
                """,
                                    code, message);

                    return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(json);
                };
        GatewayCallbackManager.setBlockHandler(handler);
    }
}
