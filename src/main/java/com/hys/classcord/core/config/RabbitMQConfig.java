package com.hys.classcord.core.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    // =========================================================================
    // 1. 教材模組主要 Exchange 與 Queue 常數
    // =========================================================================
    public static final String MATERIAL_EXCHANGE = "classcord.material.exchange";

    public static final String MOVE_QUEUE = "classcord.material.move.queue";
    public static final String ROUTING_KEY_MOVE = "material.move";

    public static final String DELETE_QUEUE = "classcord.material.delete.queue";
    public static final String ROUTING_KEY_DELETE = "material.delete";

    // =========================================================================
    // 2. 共享死信佇列 (DLX & DLQ) 常數
    // =========================================================================
    public static final String MATERIAL_DLX = "classcord.material.dlx";

    public static final String MOVE_DLQ = "classcord.material.move.dlq";
    public static final String ROUTING_KEY_MOVE_DLK = "material.move.dlk";

    public static final String DELETE_DLQ = "classcord.material.delete.dlq";
    public static final String ROUTING_KEY_DELETE_DLK = "material.delete.dlk";

    // =========================================================================
    // 3. AI 助教對話/RAG 處理佇列常數
    // =========================================================================
    public static final String AI_EXCHANGE = "classcord.ai.exchange";
    public static final String RAG_PROCESS_QUEUE = "classcord.ai.rag.process.queue";
    public static final String ROUTING_KEY_RAG_PROCESS = "ai.rag.process";

    public static final String RAG_PROCESS_DLQ = "classcord.ai.rag.process.dlq";
    public static final String ROUTING_KEY_RAG_DLK = "ai.rag.process.dlk";

    // =========================================================================
    // 4. AI 測驗與出題模組佇列常數 (Quiz Module Async Generation)
    // =========================================================================
    public static final String QUIZ_EXCHANGE = "classcord.quiz.exchange";
    public static final String QUIZ_GEN_QUEUE = "classcord.quiz.generation.queue";
    public static final String ROUTING_KEY_QUIZ_GEN = "quiz.generation";

    public static final String QUIZ_GEN_DLQ = "classcord.quiz.generation.dlq";
    public static final String ROUTING_KEY_QUIZ_GEN_DLK = "quiz.generation.dlk";

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

    /** 配置 AI 主要 Exchange */
    @Bean
    public DirectExchange aiExchange() {
        return new DirectExchange(AI_EXCHANGE);
    }

    /** 配置 AI RAG 處理佇列 (綁定 DLX) */
    @Bean
    public Queue ragProcessQueue() {
        return QueueBuilder.durable(RAG_PROCESS_QUEUE)
                .deadLetterExchange(MATERIAL_DLX) // 共享現有的死信 Exchange
                .deadLetterRoutingKey(ROUTING_KEY_RAG_DLK)
                .build();
    }

    /** 配置 AI RAG 死信佇列 */
    @Bean
    public Queue ragProcessDlq() {
        return QueueBuilder.durable(RAG_PROCESS_DLQ).build();
    }

    /** 綁定 AI 佇列 */
    @Bean
    public Binding ragProcessBinding(Queue ragProcessQueue, DirectExchange aiExchange) {
        return BindingBuilder.bind(ragProcessQueue).to(aiExchange).with(ROUTING_KEY_RAG_PROCESS);
    }

    @Bean
    public Binding ragProcessDlqBinding(Queue ragProcessDlq, DirectExchange materialDlx) {
        return BindingBuilder.bind(ragProcessDlq).to(materialDlx).with(ROUTING_KEY_RAG_DLK);
    }

    // =========================================================================
    // 5. AI 測驗與出題模組 (Quiz Module) Bean 宣告
    // =========================================================================

    /** 配置測驗模組主要 Direct Exchange */
    @Bean
    public DirectExchange quizExchange() {
        return new DirectExchange(QUIZ_EXCHANGE);
    }

    /** 配置測驗背景出題佇列 (綁定共享的死信 Exchange) */
    @Bean
    public Queue quizGenQueue() {
        return QueueBuilder.durable(QUIZ_GEN_QUEUE)
                .deadLetterExchange(MATERIAL_DLX)
                .deadLetterRoutingKey(ROUTING_KEY_QUIZ_GEN_DLK)
                .build();
    }

    /** 配置測驗出題死信佇列 (DLQ) */
    @Bean
    public Queue quizGenDlq() {
        return QueueBuilder.durable(QUIZ_GEN_DLQ).build();
    }

    /** 綁定出題佇列至主要 Exchange */
    @Bean
    public Binding quizGenBinding(Queue quizGenQueue, DirectExchange quizExchange) {
        return BindingBuilder.bind(quizGenQueue).to(quizExchange).with(ROUTING_KEY_QUIZ_GEN);
    }

    /** 綁定出題死信佇列至死信 Exchange */
    @Bean
    public Binding quizGenDlqBinding(Queue quizGenDlq, DirectExchange materialDlx) {
        return BindingBuilder.bind(quizGenDlq).to(materialDlx).with(ROUTING_KEY_QUIZ_GEN_DLK);
    }

    /** JSON 訊息轉譯器 */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
