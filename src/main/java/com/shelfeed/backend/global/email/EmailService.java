package com.shelfeed.backend.global.email;

public interface EmailService {
    // 동기 — 호출자가 발송 실패를 즉시 처리해야 하는 경로(예: 인증코드 재발송)에서 사용
    void sendVerificationEmail(String email, String code);

    // 비동기 fire-and-forget — 회원가입 등 발송 지연이 요청 응답을 막으면 안 되는 경로에서 사용
    void sendVerificationEmailAsync(String email, String code);

    // 비동기 fire-and-forget — 비밀번호 재설정 메일(요청 응답과 분리)
    void sendPasswordResetEmail(String email, String token);
}
