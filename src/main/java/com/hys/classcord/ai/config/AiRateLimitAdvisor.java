package com.hys.classcord.ai.config;

import com.hys.classcord.ai.service.AiRateLimitService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.advisor.api.AdvisedRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisedResponse;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAroundAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAroundAdvisorChain;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

@Slf4j
public class AiRateLimitAdvisor implements CallAroundAdvisor, StreamAroundAdvisor {

    private final AiRateLimitService rateLimitService;

    public AiRateLimitAdvisor(AiRateLimitService rateLimitService) {
        this.rateLimitService = rateLimitService;
    }

    @Override
    public AdvisedResponse aroundCall(AdvisedRequest advisedRequest, CallAroundAdvisorChain chain) {
        checkRateLimit();
        return chain.nextAroundCall(advisedRequest);
    }

    @Override
    public Flux<AdvisedResponse> aroundStream(
            AdvisedRequest advisedRequest, StreamAroundAdvisorChain chain) {
        checkRateLimit();
        return chain.nextAroundStream(advisedRequest);
    }

    private void checkRateLimit() {
        rateLimitService.checkChatRateLimit();
    }

    @Override
    public String getName() {
        return "AiRateLimitAdvisor";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
