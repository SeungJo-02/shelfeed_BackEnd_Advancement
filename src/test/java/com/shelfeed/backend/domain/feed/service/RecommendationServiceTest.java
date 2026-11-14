package com.shelfeed.backend.domain.feed.service;

import com.shelfeed.backend.domain.book.repository.BookRepository;
import com.shelfeed.backend.domain.genre.entity.Genre;
import com.shelfeed.backend.domain.genre.entity.MemberGenre;
import com.shelfeed.backend.domain.genre.repository.MemberGenreRepository;
import com.shelfeed.backend.domain.library.repository.LibraryRepository;
import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.review.entity.Review;
import com.shelfeed.backend.domain.review.repository.ReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

/**
 * 온보딩 장르의 category_pattern은 정규식({@code [>-](자연)?과학} 등)이라 {@code books.genre}와 등가 비교가 안 된다.
 * 추천은 패턴을 실제 genre 값으로 풀어서 IN 절에 넣어야 한다 — 그 변환을 고정한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecommendationService — 장르 패턴 → 실제 genre 값 해석")
class RecommendationServiceTest {

    @Mock LibraryRepository libraryRepository;
    @Mock BookRepository bookRepository;
    @Mock ReviewRepository reviewRepository;
    @Mock MemberGenreRepository memberGenreRepository;

    @InjectMocks RecommendationService service;

    Member me;

    @BeforeEach
    void setUp() {
        me = Member.createLocal(1L, "me@test.com", "pw", "me", null);
        ReflectionTestUtils.setField(me, "memberId", 101L);
    }

    private MemberGenre memberGenre(String pattern) {
        Genre g = mock(Genre.class);
        lenient().when(g.getCategoryPattern()).thenReturn(pattern);
        MemberGenre mg = mock(MemberGenre.class);
        lenient().when(mg.getGenre()).thenReturn(g);
        return mg;
    }

    @Test
    @DisplayName("패턴 하나가 여러 genre 값으로 펼쳐지고, 빈 패턴은 조회하지 않는다")
    void 패턴_해석() {
        given(bookRepository.findDistinctGenresByCategoryPattern("[>-](자연)?과학"))
                .willReturn(java.util.Arrays.asList("과학", "자연과학", null, "과학", " "));  // List.of는 null 불가

        assertThat(service.resolveGenreValues("[>-](자연)?과학")).containsExactly("과학", "자연과학");
        assertThat(service.resolveGenreValues("  ")).isEmpty();
        assertThat(service.resolveGenreValues(null)).isEmpty();
        then(bookRepository).should(times(1)).findDistinctGenresByCategoryPattern(anyString());
    }

    @Test
    @DisplayName("IN 절에는 패턴 문자열이 아니라 해석된 genre 값이 들어간다")
    @SuppressWarnings("unchecked")
    void IN절에는_실제_genre_값() {
        // 모의 객체는 given(...) 밖에서 먼저 만든다 — 스터빙 안에서 스터빙하면 Mockito가 막는다.
        MemberGenre economy = memberGenre("경제 ?경영");
        MemberGenre humanities = memberGenre("인문");
        given(libraryRepository.findReadBookIdsByMember(101L)).willReturn(List.of());
        given(memberGenreRepository.findAllByMemberWithGenre(me)).willReturn(List.of(economy, humanities));
        given(bookRepository.findDistinctGenresByCategoryPattern("경제 ?경영")).willReturn(List.of("경제경영", "경제 경영"));
        given(bookRepository.findDistinctGenresByCategoryPattern("인문")).willReturn(List.of("인문학", "인문"));
        given(reviewRepository.findRecommendedByGenres(anyList(), eq(me), isNull(), isNull(), any(Pageable.class)))
                .willReturn(List.of(mock(Review.class), mock(Review.class), mock(Review.class)));

        service.findCandidates(me, null, null, 5);

        ArgumentCaptor<List<String>> genres = ArgumentCaptor.forClass(List.class);
        then(reviewRepository).should().findRecommendedByGenres(genres.capture(), eq(me), isNull(), isNull(), any(Pageable.class));
        assertThat(genres.getValue())
                .containsExactlyInAnyOrder("경제경영", "경제 경영", "인문학", "인문")
                .doesNotContain("경제 ?경영");
        then(reviewRepository).should(never()).findPopularRecent(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("서재 장르(가중치 3)와 겹치는 해석 값은 점수가 합산되고, 상위 5개만 IN 절에 남는다")
    @SuppressWarnings("unchecked")
    void 가중치_합산_및_상위_5개_제한() {
        given(libraryRepository.findReadBookIdsByMember(101L)).willReturn(List.of(1L, 2L));
        // 서재에서 '소설/시/희곡' 2권(→ 6점), '역사' 1권(→ 3점)
        given(bookRepository.countGenresByBookIds(anyCollection(), any(Pageable.class)))
                .willReturn(List.<Object[]>of(new Object[]{"소설/시/희곡", 2L}, new Object[]{"역사", 1L}));
        // 온보딩: 소설(→ 소설/시/희곡 +1 = 7점), 과학(→ 과학·자연과학·물리학·화학 각 1점)
        MemberGenre novel = memberGenre("소설/시/희곡");
        MemberGenre science = memberGenre("[>-](자연)?과학");
        given(memberGenreRepository.findAllByMemberWithGenre(me)).willReturn(List.of(novel, science));
        given(bookRepository.findDistinctGenresByCategoryPattern("소설/시/희곡")).willReturn(List.of("소설/시/희곡"));
        given(bookRepository.findDistinctGenresByCategoryPattern("[>-](자연)?과학"))
                .willReturn(List.of("과학", "자연과학", "물리학", "화학"));
        given(reviewRepository.findRecommendedByGenres(anyList(), eq(me), isNull(), isNull(), any(Pageable.class)))
                .willReturn(List.of(mock(Review.class), mock(Review.class), mock(Review.class)));

        service.findCandidates(me, null, null, 5);

        ArgumentCaptor<List<String>> genres = ArgumentCaptor.forClass(List.class);
        then(reviewRepository).should().findRecommendedByGenres(genres.capture(), eq(me), isNull(), isNull(), any(Pageable.class));
        List<String> top = genres.getValue();
        assertThat(top).hasSize(5);
        assertThat(top.get(0)).isEqualTo("소설/시/희곡");   // 6 + 1
        assertThat(top.get(1)).isEqualTo("역사");           // 3
        assertThat(top.subList(2, 5)).isSubsetOf("과학", "자연과학", "물리학", "화학"); // 1점짜리 4개 중 3개만
    }

    @Test
    @DisplayName("해석 결과가 모두 비면(패턴에 걸리는 책이 없음) cold-start 경로로 간다")
    void 해석_결과_없으면_cold_start() {
        MemberGenre religion = memberGenre("종교");
        given(libraryRepository.findReadBookIdsByMember(101L)).willReturn(List.of());
        given(memberGenreRepository.findAllByMemberWithGenre(me)).willReturn(List.of(religion));
        given(bookRepository.findDistinctGenresByCategoryPattern("종교")).willReturn(List.of());
        given(reviewRepository.findPopularRecent(any(), eq(me), isNull(), isNull(), any(Pageable.class))).willReturn(List.of());

        service.findCandidates(me, null, null, 5);

        then(reviewRepository).should().findPopularRecent(any(), eq(me), isNull(), isNull(), any(Pageable.class));
        then(reviewRepository).should(never()).findRecommendedByGenres(anyList(), any(), any(), any(), any());
    }
}
