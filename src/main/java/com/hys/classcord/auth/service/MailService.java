package com.hys.classcord.auth.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

@Service
@RequiredArgsConstructor
public class MailService {

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine; // 🚀 注入 Thymeleaf 模板引擎

    private final String fromEmail = "noreply@classcord.hys-lab.com";

    /** 🚀 業界標準：傳入參數，動態渲染 HTML 模板並寄出 */
    @Async
    public void sendAuthMail(
            String to,
            String subject,
            String type,
            String title,
            String content,
            String buttonText,
            String actionLink,
            String warningText) {
        try {
            // 1. 準備 Thymeleaf 的上下文變數容器
            Context context = new Context();
            context.setVariable("type", type);
            context.setVariable("title", title);
            context.setVariable("content", content);
            context.setVariable("buttonText", buttonText);
            context.setVariable("actionLink", actionLink);
            context.setVariable("warningText", warningText);

            // 2. 指定模板名稱「auth-mail」（Thymeleaf 會自動去 templates/ 找 auth-mail.html）
            String htmlContent = templateEngine.process("mail/auth-mail", context);

            // 3. 寄出信件
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(fromEmail);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlContent, true); // 塞入渲染後的 HTML 字串

            mailSender.send(message);
            System.out.println("📧 [MailService] 成功透過 Thymeleaf 模板非同步發送郵件至: " + to);

        } catch (MessagingException e) {
            throw new RuntimeException("郵件發送異常", e);
        }
    }
}
