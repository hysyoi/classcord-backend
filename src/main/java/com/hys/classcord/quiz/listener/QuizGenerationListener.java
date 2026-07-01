package com.hys.classcord.quiz.listener;

import com.hys.classcord.core.config.RabbitMQConfig;
import com.hys.classcord.quiz.dto.QuizGenerationMessage;
import com.hys.classcord.quiz.service.QuizService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class QuizGenerationListener {

    private final QuizService quizService;

    @RabbitListener(queues = RabbitMQConfig.QUIZ_GEN_QUEUE)
    public void onQuizGenerationMessage(QuizGenerationMessage message) {
        log.info(
                "從 RabbitMQ 收到 AI 出題任務: jobId={}, materialId={}",
                message.jobId(),
                message.materialId());
        try {
            quizService.executeQuizGenerationBackground(
                    message.jobId(), message.materialId(), message.count(), message.difficulty());
        } catch (Exception e) {
            log.error("AI 出題背景處理發生異常: jobId={}", message.jobId(), e);
            // 由於 executeQuizGenerationBackground 自行處理了異常並標記 Job 失敗，
            // 這裡不再往外拋出，避免訊息無窮重試，只進行 Log 記錄。
        }
    }
}
