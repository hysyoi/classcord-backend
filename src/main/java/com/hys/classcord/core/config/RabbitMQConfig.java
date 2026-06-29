package com.hys.classcord.core.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    // 1. 主要 Exchange 與 Queue 常數
    public static final String MATERIAL_EXCHANGE = "classcord.material.exchange";

    public static final String MOVE_QUEUE = "classcord.material.move.queue";
    public static final String ROUTING_KEY_MOVE = "material.move";

    public static final String DELETE_QUEUE = "classcord.material.delete.queue";
    public static final String ROUTING_KEY_DELETE = "material.delete";

    // 2. 死信佇列 (DLX & DLQ) 常數
    public static final String MATERIAL_DLX = "classcord.material.dlx";

    public static final String MOVE_DLQ = "classcord.material.move.dlq";
    public static final String ROUTING_KEY_MOVE_DLK = "material.move.dlk";

    public static final String DELETE_DLQ = "classcord.material.delete.dlq";
    public static final String ROUTING_KEY_DELETE_DLK = "material.delete.dlk";

    /** 配置主要 Direct Exchange */
    @Bean
    public DirectExchange materialExchange() {
        return new DirectExchange(MATERIAL_EXCHANGE);
    }

    /** 配置死信 Exchange (DLX) */
    @Bean
    public DirectExchange materialDlx() {
        return new DirectExchange(MATERIAL_DLX);
    }

    /** 配置搬移檔案佇列 (綁定 DLX) */
    @Bean
    public Queue moveQueue() {
        return QueueBuilder.durable(MOVE_QUEUE)
                .deadLetterExchange(MATERIAL_DLX)
                .deadLetterRoutingKey(ROUTING_KEY_MOVE_DLK)
                .build();
    }

    /** 配置刪除檔案佇列 (綁定 DLX) */
    @Bean
    public Queue deleteQueue() {
        return QueueBuilder.durable(DELETE_QUEUE)
                .deadLetterExchange(MATERIAL_DLX)
                .deadLetterRoutingKey(ROUTING_KEY_DELETE_DLK)
                .build();
    }

    /** 配置搬移死信佇列 (DLQ) */
    @Bean
    public Queue moveDlq() {
        return QueueBuilder.durable(MOVE_DLQ).build();
    }

    /** 配置刪除死信佇列 (DLQ) */
    @Bean
    public Queue deleteDlq() {
        return QueueBuilder.durable(DELETE_DLQ).build();
    }

    /** 綁定主要佇列 */
    @Bean
    public Binding moveBinding(Queue moveQueue, DirectExchange materialExchange) {
        return BindingBuilder.bind(moveQueue).to(materialExchange).with(ROUTING_KEY_MOVE);
    }

    @Bean
    public Binding deleteBinding(Queue deleteQueue, DirectExchange materialExchange) {
        return BindingBuilder.bind(deleteQueue).to(materialExchange).with(ROUTING_KEY_DELETE);
    }

    /** 綁定死信佇列至 DLX */
    @Bean
    public Binding moveDlqBinding(Queue moveDlq, DirectExchange materialDlx) {
        return BindingBuilder.bind(moveDlq).to(materialDlx).with(ROUTING_KEY_MOVE_DLK);
    }

    @Bean
    public Binding deleteDlqBinding(Queue deleteDlq, DirectExchange materialDlx) {
        return BindingBuilder.bind(deleteDlq).to(materialDlx).with(ROUTING_KEY_DELETE_DLK);
    }

    /** JSON 訊息轉譯器 */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
