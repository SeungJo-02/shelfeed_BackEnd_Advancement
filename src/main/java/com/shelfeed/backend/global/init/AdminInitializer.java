package com.shelfeed.backend.global.init;

import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.member.repository.MemberRepository;
import com.shelfeed.backend.global.redis.RedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import com.shelfeed.backend.domain.member.service.MemberUserIdGenerator;

@Slf4j
@Component
@Order(1)//어드민 계정부터 1순위로 가장 먼저 만들겠다는 의미
@RequiredArgsConstructor
public class AdminInitializer implements ApplicationRunner {//run 메서드를 오버라이딩하면, 스프링 부트 서버가 완전히 켜진 직후에 해당 코드가 자동으로 딱 한 번 실행

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final RedisService redisService;
    private final MemberUserIdGenerator memberUserIdGenerator;

    @Value("${admin.email}")
    private String adminEmail;

    @Value("${admin.password}")
    private String adminPassword;

    @Override
    public void run(ApplicationArguments args) {
        if (adminEmail == null || adminEmail.isBlank()) {
            throw new IllegalStateException("[AdminInitializer] ADMIN_EMAIL이 설정되지 않았습니다.");
        }
        if (!adminEmail.trim().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new IllegalStateException("[AdminInitializer] ADMIN_EMAIL 형식이 올바르지 않습니다: " + adminEmail);
        }
        if (adminPassword == null || adminPassword.length() < 8) {
            throw new IllegalStateException("[AdminInitializer] ADMIN_PASSWORD는 8자 이상이어야 합니다.");
        }

        if (memberRepository.existsByEmail(adminEmail)) {
            log.info("[AdminInitializer] 어드민 계정이 이미 존재합니다. 건너뜁니다.");
            return;
        }

        Long userId = memberUserIdGenerator.next();
        Member admin = Member.createLocal(userId, adminEmail,
                passwordEncoder.encode(adminPassword), "관리자", "Shelfeed 관리자 계정");
        admin.verifyEmail();
        admin.completeOnboarding();
        admin.promoteToAdmin();
        memberRepository.save(admin);

        log.info("[AdminInitializer] 어드민 계정 생성 완료: {}", adminEmail);
    }
}
