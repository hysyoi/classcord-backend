package com.hys.classcord.message.consumer;

import com.hys.classcord.core.config.RabbitMQConfig;
import com.hys.classcord.message.dto.MessageSaveTask;
import com.hys.classcord.message.service.MessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class MessageConsumer {

    private final MessageService messageService;

    /** 監聽訊息落庫佇列，背景執行訊息非同步存儲 */
    @RabbitListener(queues = RabbitMQConfig.MESSAGE_SAVE_QUEUE)
    public void handleMessageSave(MessageSaveTask task) {
        log.info(
                "[MQ Consumer] 收到訊息儲存任務, 訊息ID: {}, 用戶ID: {}, 頻道ID: {}",
                task.messageId(),
                task.userId(),
                task.channelId());
        try {
            messageService.saveMessageAsync(task);
        } catch (Exception e) {
            log.error("[MQ Consumer] 訊息儲存失敗, 訊息ID: {}", task.messageId(), e);
            throw e; // 丟出異常觸發 Spring AMQP 重試或投遞至死信佇列
        }
    }
}
