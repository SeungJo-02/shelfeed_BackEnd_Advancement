package com.shelfeed.backend.domain.genre;

import com.shelfeed.backend.domain.genre.entity.Genre;
import com.shelfeed.backend.domain.genre.entity.MemberGenre;
import com.shelfeed.backend.domain.genre.repository.GenreRepository;
import com.shelfeed.backend.domain.genre.repository.MemberGenreRepository;
import com.shelfeed.backend.domain.member.dto.request.OnboardingRequest;
import com.shelfeed.backend.domain.member.dto.request.UpdateGenresRequest;
import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.member.repository.MemberRepository;
import com.shelfeed.backend.domain.member.service.MemberService;
import com.shelfeed.backend.global.redis.RedisService;
import com.shelfeed.backend.support.TestContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 관심 장르 저장/수정 + 온보딩의 영속성 동작을 실제 DB(Testcontainers)로 검증하는 통합 테스트.
 *
 * Mockito 단위 테스트(MemberServiceGenreTest)는 deleteAllByMember/saveAll의 실제 flush·clear
 * 동작을 재현하지 못해 아래 두 회귀(#198 코드리뷰 발견)를 잡지 못했다. 본 테스트가 그 갭을 메운다:
 *  - HIGH: deleteAllByMember의 clearAutomatically=true가 member를 detach시켜 completeOnboarding()의
 *          onboardingCompleted=true가 DB에 저장되지 않던 회귀
 *  - 409: delete가 saveAll INSERT보다 늦게 flush되어 member_genres 복합 unique 위반
 */
@SpringBootTest
@DisplayName("관심 장르 + 온보딩 영속성 통합 테스트 (#198 회귀)")
class MemberGenreOnboardingIntegrationTest extends TestContainerSupport {

    @Autowired MemberService memberService;
    @Autowired MemberRepository memberRepository;
    @Autowired GenreRepository genreRepository;
    @Autowired MemberGenreRepository memberGenreRepository;

    // 본 테스트의 검증 대상은 JPA 영속성(detach/flush)이라 Redis는 불필요.
    // 로컬에 Redis가 없어도 컨텍스트가 뜨도록 mock으로 대체(검증 경로엔 Redis 호출 없음).
    @MockBean RedisService redisService;

    private Member member;
    private Genre g1;
    private Genre g2;
    private Genre g3;

    @BeforeEach
    void setUp() {
        // ddl-auto=create-drop은 클래스당 1회만 스키마를 만들므로 테스트 간 데이터가 남는다.
        // 각 테스트가 같은 memberUserId/email을 재사용하므로 매 테스트 시작 시 명시적으로 비워 격리한다.
        // (이 테스트는 flush/detach 동작 검증이 목적이라 @Transactional 롤백 격리는 쓸 수 없음)
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
