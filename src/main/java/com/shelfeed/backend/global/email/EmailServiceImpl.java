package com.shelfeed.backend.global.email;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailServiceImpl implements EmailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String from;

    @Value("${app.frontend.url}")
    private String frontendUrl;

    @Value("${app.mail.bcc:}")
    private String bcc;

    @Override
    public void sendVerificationEmail(String email, String code) {
        send(email, "[Shelfeed] 이메일 인증 코드", buildVerificationBody(code));
    }

    @Override
    @Async("mailExecutor")
    public void sendVerificationEmailAsync(String email, String code) {
        try {
            send(email, "[Shelfeed] 이메일 인증 코드", buildVerificationBody(code));
        } catch (EmailSendException e) {
            log.warn("[EMAIL] 인증 이메일 비동기 발송 실패 — 재발송 API로 재시도 가능: {}",
                    e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
        }
    }

    @Override
    @Async("mailExecutor")
    public void sendPasswordResetEmail(String email, String token) {
        try {
            send(email, "[Shelfeed] 비밀번호 재설정", buildPasswordResetBody(token));
        } catch (EmailSendException e) {
            log.warn("[EMAIL] 비밀번호 재설정 이메일 비동기 발송 실패: {}",
                    e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
        }
    }

    private void send(String to, String subject, String htmlBody) {
        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            if (bcc != null && !bcc.isBlank()) {
                helper.setBcc(bcc);
            }
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            mailSender.send(message);
        } catch (MessagingException | MailException e) {
            throw new EmailSendException("이메일 발송 실패", e);
        }
    }

    private String buildVerificationBody(String code) {
        return """
            <div style="font-family:sans-serif;max-width:480px;margin:0 auto;padding:32px;">
              <h2 style="color:#3B3B3B;">Shelfeed 이메일 인증</h2>
              <p style="color:#555;">아래 인증 코드를 입력해 주세요. 코드는 <b>5분</b>간 유효합니다.</p>
              <div style="background:#F5F5F5;border-radius:8px;padding:24px;text-align:center;
                          font-size:32px;font-weight:bold;letter-spacing:8px;color:#2563EB;margin:24px 0;">
                %s
              </div>
              <p style="font-size:12px;color:#aaa;">본인이 요청하지 않은 경우 이 메일을 무시하세요.</p>
            </div>
            """.formatted(code);
    }

    private String buildPasswordResetBody(String token) {
        String link = frontendUrl + "/auth/reset-password?token=" + token;
        return """
            <div style="font-family:sans-serif;max-width:480px;margin:0 auto;padding:32px;">
              <h2 style="color:#3B3B3B;">Shelfeed 비밀번호 재설정</h2>
              <p style="color:#555;">아래 버튼을 클릭하여 비밀번호를 재설정하세요. 링크는 <b>30분</b>간 유효합니다.</p>
              <a href="%s"
                 style="display:inline-block;margin-top:16px;padding:12px 28px;
                        background:#2563EB;color:#fff;border-radius:6px;
                        text-decoration:none;font-size:15px;">
                비밀번호 재설정
              </a>
              <p style="margin-top:24px;font-size:12px;color:#aaa;">
                본인이 요청하지 않은 경우 이 메일을 무시하세요.
              </p>
            </div>
            """.formatted(link);
    }
}
