package com.shelfeed.backend.domain.member;

import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.member.repository.MemberRepository;
import com.shelfeed.backend.domain.member.repository.MemberUserIdSequenceRepository;
import com.shelfeed.backend.domain.member.service.MemberUserIdGenerator;
import com.shelfeed.backend.global.config.JpaConfig;
import com.shelfeed.backend.support.TestContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공개 회원 번호 발급 통합 테스트.
 *
 * <p>발급을 Redis {@code INCR}에서 DB 시퀀스로 옮긴 이유를 그대로 테스트로 고정한다.
 * Redis 카운터가 유실되면 이미 쓰인 번호를 다시 내주어 회원 저장이 unique 제약에 걸리고,
 * 회원가입 전체가 409로 막혔다.
 *
 * <p>{@code LAST_INSERT_ID()}는 MySQL 문법이라 Testcontainers의 실제 MySQL에서 검증한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({MemberUserIdGenerator.class, JpaConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("회원 번호 발급 (DB 시퀀스) 통합 테스트")
class MemberUserIdGeneratorTest extends TestContainerSupport {

    @Autowired MemberUserIdGenerator generator;
    @Autowired MemberUserIdSequenceRepository sequenceRepository;
    @Autowired MemberRepository memberRepository;

    @BeforeEach
    void setUp() {
        // NOT_SUPPORTED라 각 테스트가 독립 커밋된다. 매 테스트 시작 시 비워 격리.
        memberRepository.deleteAllInBatch();
        sequenceRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("시퀀스가 비어 있어도 첫 번호를 발급한다")
    void 최초_발급() {
        assertThat(generator.next()).isEqualTo(1L);
    }

    @Test
    @DisplayName("연속 발급은 겹치지 않고 증가한다")
    void 연속_발급_중복없음() {
        List<Long> issued = new ArrayList<>();
        for (int i = 0; i < 20; i++) issued.add(generator.next());

        assertThat(issued).doesNotHaveDuplicates();
        assertThat(issued).isSorted();
    }

    @Test
    @DisplayName("이미 쓰인 번호보다 시퀀스가 뒤처져 있으면 기동 정렬이 끌어올린다")
    void 뒤처진_시퀀스_기동시_복구() {
        // Redis 카운터가 유실돼 1부터 다시 세던 상황을 재현한다:
        // 시퀀스는 낮은 값에 머물러 있는데, 회원 테이블에는 훨씬 큰 번호가 이미 쓰여 있다.
        generator.next();
        memberRepository.save(
                Member.createLocal(5_000L, "existing@test.com", "encoded", "기존회원", null));

        generator.alignSequenceOnStartup();

        assertThat(generator.next())
                .as("이미 존재하는 회원 번호를 다시 발급하면 unique 제약에 걸린다")
                .isGreaterThan(5_000L);
    }

    @Test
    @DisplayName("정렬 후 발급한 번호로 회원을 저장할 수 있다")
    void 정렬후_회원저장_성공() {
        memberRepository.save(
                Member.createLocal(7_777L, "old@test.com", "encoded", "옛회원", null));
        generator.alignSequenceOnStartup();

        Long next = generator.next();
        memberRepository.save(Member.createLocal(next, "new@test.com", "encoded", "새회원", null));

        assertThat(memberRepository.findByMemberUserId(next)).isPresent();
    }
}
