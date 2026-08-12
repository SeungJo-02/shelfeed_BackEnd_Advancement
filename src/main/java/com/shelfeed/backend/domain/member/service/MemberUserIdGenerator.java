package com.shelfeed.backend.domain.member.service;

import com.shelfeed.backend.domain.member.repository.MemberUserIdSequenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공개 회원 번호를 발급한다.
 *
 * <p>회원 저장과 같은 트랜잭션 안에서 DB 시퀀스를 올리므로, 회원가입이 롤백되면 번호도 함께
 * 되돌아가고, 애플리케이션이 재시작해도 이어서 발급된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemberUserIdGenerator {

    private final MemberUserIdSequenceRepository sequenceRepository;

    /**
     * 다음 회원 번호를 발급한다.
     *
     * <p>{@code REQUIRED}로 트랜잭션을 보장하는 것이 중요하다 — 증가와 조회가 같은 커넥션에서
     * 일어나야 {@code LAST_INSERT_ID()}가 방금 올린 값을 돌려준다.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public Long next() {
        if (sequenceRepository.bump() == 0) {
            // 첫 발급이거나 시퀀스 행이 유실된 경우
            sequenceRepository.createIfAbsent();
            sequenceRepository.bump();
        }
        return sequenceRepository.lastIssued();
    }

    /**
     * 기동 시 시퀀스를 실제 회원 번호에 맞춰 보정한다.
     *
     * <p>Redis로 번호를 발급하던 시절에 카운터가 유실된 환경은 이미 쓰인 번호를 다시 내주어
     * 회원가입이 unique 제약으로 막혀 있다. 배포만으로 그 상태가 풀리도록 여기서 끌어올린다.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void alignSequenceOnStartup() {
        sequenceRepository.createIfAbsent();
        sequenceRepository.alignWithMembers();
        log.info("[MemberUserIdGenerator] 회원 번호 시퀀스 정렬 완료");
    }
}
