package com.hys.classcord.ai.consumer;

import com.hys.classcord.ai.service.QuizAiService;
import com.hys.classcord.core.config.RabbitMQConfig;
import com.hys.classcord.quiz.dto.QuizGenerationMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class QuizAiConsumer {

    private final QuizAiService quizAiService;

    @RabbitListener(queues = RabbitMQConfig.QUIZ_GEN_QUEUE)
    public void handleQuizGenerationMessage(QuizGenerationMessage message) {
        log.info("【AI 微服務 MQ Consumer】收到背景出題請求: jobId={}, materialId={}, count={}",
                message.jobId(), message.materialId(), message.count());
        quizAiService.generateQuestions(message);
    }
}
