package com.shelfeed.backend.domain.genre;

import com.shelfeed.backend.domain.genre.entity.Genre;
import com.shelfeed.backend.domain.genre.repository.GenreRepository;
import com.shelfeed.backend.domain.genre.repository.MemberGenreRepository;
import com.shelfeed.backend.domain.member.dto.request.OnboardingRequest;
import com.shelfeed.backend.domain.member.dto.request.UpdateGenresRequest;
import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.member.repository.MemberRepository;
import com.shelfeed.backend.domain.member.service.MemberService;
import com.shelfeed.backend.global.config.JpaConfig;
import com.shelfeed.backend.global.jwt.JwtProvider;
import com.shelfeed.backend.global.redis.RedisService;
import com.shelfeed.backend.support.TestContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 관심 장르 저장/수정 + 온보딩의 영속성 동작을 실제 DB(Testcontainers MySQL)로 검증한다.
 *
 * Mockito 단위 테스트(MemberServiceGenreTest)는 deleteAllByMember/saveAll의 실제 flush·clear
 * 동작을 재현하지 못해 아래 두 회귀(#198 코드리뷰 발견)를 잡지 못했다. 본 테스트가 그 갭을 메운다:
 *  - HIGH: deleteAllByMember의 clearAutomatically=true가 member를 detach시켜 completeOnboarding()의
 *          onboardingCompleted=true가 DB에 저장되지 않던 회귀
 *  - 409: delete가 saveAll INSERT보다 늦게 flush되어 member_genres 복합 unique 위반
 *
 * @DataJpaTest 슬라이스를 쓰는 이유: @SpringBootTest 풀 컨텍스트는 CLOVA/admin 등 외부 시크릿에
 * 의존하는 빈까지 로드해 CI(시크릿 미설정)에서 컨텍스트 로딩이 실패한다. JPA 슬라이스 + 서비스만
 * import해 그 의존을 끊는다. JPA 외 의존(Redis/JWT/PasswordEncoder)은 @MockBean으로 대체.
 *
 * @Transactional(NOT_SUPPORTED): @DataJpaTest 기본 트랜잭션을 끈다. 켜두면 서비스의 @Transactional이
 * 테스트 트랜잭션에 합류해 flush/clear 타이밍이 실제 운영과 달라져 회귀를 재현하지 못한다.
 * 대신 각 테스트가 독립 트랜잭션으로 커밋되므로 @BeforeEach에서 명시적으로 데이터를 비운다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({MemberService.class, JpaConfig.class}) // JpaConfig: @EnableJpaAuditing (created_at 자동 채움) + JPAQueryFactory
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("관심 장르 + 온보딩 영속성 통합 테스트 (#198 회귀)")
class MemberGenreOnboardingIntegrationTest extends TestContainerSupport {

    @Autowired MemberService memberService;
    @Autowired MemberRepository memberRepository;
    @Autowired GenreRepository genreRepository;
    @Autowired MemberGenreRepository memberGenreRepository;

    // MemberService의 비-JPA 의존성. 본 테스트 경로(온보딩/장르 수정)에선 호출되지 않으므로 mock으로 충분.
    @MockBean RedisService redisService;
    @MockBean JwtProvider jwtProvider;
    @MockBean PasswordEncoder passwordEncoder;

    private Member member;
    private Genre g1;
    private Genre g2;
    private Genre g3;

    @BeforeEach
    void setUp() {
        // NOT_SUPPORTED라 각 테스트가 독립 커밋되어 데이터가 누적된다. 매 테스트 시작 시 명시적으로 비워 격리.
        memberGenreRepository.deleteAllInBatch();
        memberRepository.deleteAllInBatch();
        genreRepository.deleteAllInBatch();

        // 가입 시 bio 입력한 로컬 회원
        member = memberRepository.save(
                Member.createLocal(1001L, "onboard@test.com", "encoded", "초기닉", "가입때 입력한 소개글"));
        g1 = genreRepository.save(genre("소설"));
        g2 = genreRepository.save(genre("판타지"));
        g3 = genreRepository.save(genre("에세이"));
    }

    @Test
    @DisplayName("온보딩 완료 후 DB의 onboardingCompleted가 true로 저장된다 (clearAutomatically detach 회귀 방지)")
    void 온보딩완료_DB영속화() {
        OnboardingRequest request = onboarding("새닉네임", null, List.of(g1.getGenreId(), g2.getGenreId()));

        memberService.completeOnboarding(member.getMemberUserId(), request);

        // 영속성 컨텍스트가 아닌 DB에서 다시 읽어 검증 (detach된 in-memory 값에 속지 않도록)
        Member reloaded = memberRepository.findByMemberUserId(member.getMemberUserId()).orElseThrow();
        assertThat(reloaded.isOnboardingCompleted()).isTrue();
        assertThat(reloaded.getNickname()).isEqualTo("새닉네임");
    }

    @Test
    @DisplayName("온보딩 시 bio 미전달(null)이면 가입 때 입력한 소개글이 유지된다")
    void 온보딩_bio_null이면_기존값_유지() {
        OnboardingRequest request = onboarding("새닉네임", null, List.of(g1.getGenreId()));

        memberService.completeOnboarding(member.getMemberUserId(), request);

        Member reloaded = memberRepository.findByMemberUserId(member.getMemberUserId()).orElseThrow();
        assertThat(reloaded.getBio()).isEqualTo("가입때 입력한 소개글");
    }

    @Test
    @DisplayName("관심 장르를 반복 수정해도 409(unique 위반) 없이 전체 교체된다 (flush 순서 회귀 방지)")
    void 장르_반복수정_멱등() {
        // 1차: g1, g2
        memberService.updateMyGenres(member.getMemberUserId(), updateGenres(List.of(g1.getGenreId(), g2.getGenreId())));
        // 2차: g2, g3 (g2가 겹침 — 기존 행이 안 지워지면 복합 unique 위반)
        assertThatCode(() ->
                memberService.updateMyGenres(member.getMemberUserId(), updateGenres(List.of(g2.getGenreId(), g3.getGenreId())))
        ).doesNotThrowAnyException();

        List<Long> saved = memberGenreRepository.findAllByMemberWithGenre(member).stream()
                .map(mg -> mg.getGenre().getGenreId())
                .toList();
        assertThat(saved).containsExactlyInAnyOrder(g2.getGenreId(), g3.getGenreId());
    }

    // ── helpers ──────────────────────────────────────────────
    private Genre genre(String name) {
        Genre genre = newGenre();
        ReflectionTestUtils.setField(genre, "genreName", name);
        return genre;
    }

    private Genre newGenre() {
        try {
            var ctor = Genre.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private OnboardingRequest onboarding(String nickname, String bio, List<Long> genreIds) {
        OnboardingRequest r = new OnboardingRequest();
        ReflectionTestUtils.setField(r, "nickname", nickname);
        ReflectionTestUtils.setField(r, "bio", bio);
        ReflectionTestUtils.setField(r, "genreIds", genreIds);
        return r;
    }

    private UpdateGenresRequest updateGenres(List<Long> genreIds) {
        UpdateGenresRequest r = new UpdateGenresRequest();
        ReflectionTestUtils.setField(r, "genreIds", genreIds);
        return r;
    }
}
