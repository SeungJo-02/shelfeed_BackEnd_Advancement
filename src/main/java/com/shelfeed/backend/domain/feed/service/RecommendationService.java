package com.shelfeed.backend.domain.feed.service;

import com.shelfeed.backend.domain.genre.repository.MemberGenreRepository;
import com.shelfeed.backend.domain.book.repository.BookRepository;
import com.shelfeed.backend.domain.library.repository.LibraryRepository;
import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.review.entity.Review;
import com.shelfeed.backend.domain.review.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 통합 피드에 섞어 넣을 추천 감상 후보를 고른다.
 *
 * <p>예전에는 이 서비스가 독립된 "추천 탭"의 응답을 좋아요 순으로 직접 만들었지만,
 * 지금은 후보 선별만 담당한다. 최종 노출 순서는 {@link FeedService}가 팔로잉 감상과
 * 합친 뒤 작성 시각 기준으로 일괄 결정한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecommendationService {

    private final LibraryRepository libraryRepository;
    private final BookRepository bookRepository;
    private final ReviewRepository reviewRepository;
    private final MemberGenreRepository memberGenreRepository;

    private static final int TOP_CATEGORY_LIMIT = 5;
    private static final long LIBRARY_WEIGHT    = 3L;
    private static final long GENRE_WEIGHT      = 1L;
    private static final long RECENT_DAYS       = 30L;

    /**
     * 추천 후보를 (createdAt, reviewId) DESC로 최대 {@code limit}건 반환한다.
     *
     * <p>관심 장르를 뽑을 수 있으면 장르 기반으로 고르고, 결과가 절반에도 못 미치면
     * 팔로우 유저의 서재 기반 후보로 보충한다. 온보딩 전이라 장르를 모르면 최근
     * 30일 감상으로 폴백한다.
     *
     * @param cursorCreatedAt 이 시각보다 과거의 감상만 (null이면 첫 페이지)
     * @param cursorId        같은 시각일 때 이 ID보다 작은 감상만
     */
    public List<Review> findCandidates(Member me, LocalDateTime cursorCreatedAt,
                                       Long cursorId, int limit) {
        if (limit <= 0) return List.of();

        List<String> topCategories = buildTopCategories(me);
        if (topCategories.isEmpty()) {
            // Cold-start: 관심 장르를 아직 모를 때는 최근 감상으로 채운다.
            return reviewRepository.findPopularRecent(
                    LocalDateTime.now().minusDays(RECENT_DAYS),
                    me, cursorCreatedAt, cursorId, PageRequest.of(0, limit));
        }

        List<Review> byGenre = reviewRepository.findRecommendedByGenres(
                topCategories, me, cursorCreatedAt, cursorId, PageRequest.of(0, limit));
        if (byGenre.size() >= limit / 2) {
            return byGenre;
        }

        // 장르 후보가 부족하면 소셜(팔로우 유저 서재) 후보로 보충한다.
        List<Review> social = reviewRepository.findRecommendedByFolloweeLibrary(
                me, cursorCreatedAt, cursorId, PageRequest.of(0, limit));

        Set<Long> seen = byGenre.stream().map(Review::getReviewId).collect(Collectors.toSet());
        List<Review> merged = new ArrayList<>(byGenre);
        social.stream()
                .filter(r -> !seen.contains(r.getReviewId()))
                .forEach(merged::add);

        merged.sort(FeedService.NEWEST_FIRST);
        return merged.size() > limit ? merged.subList(0, limit) : merged;
    }

    // 서재 장르(가중치 3) + 온보딩 장르(가중치 1) 합산 → Top-5
    private List<String> buildTopCategories(Member me) {
        Map<String, Long> scoreMap = new LinkedHashMap<>();

        // 서재↔도서 조인이 서비스 경계를 넘게 되어 2단계로 나눴다.
        // 1) 서재에서 읽은 도서 ID → 2) catalog에서 장르 빈도 집계
        List<Long> readBookIds = libraryRepository.findReadBookIdsByMember(me.getMemberId());
        if (!readBookIds.isEmpty()) {
            bookRepository.countGenresByBookIds(readBookIds, PageRequest.of(0, 20))
                    .forEach(row -> scoreMap.merge(
                            (String) row[0], (Long) row[1] * LIBRARY_WEIGHT, Long::sum));
        }

        // data.sql에 category_pattern 채워져 있음. 온보딩 완료 회원에게 자동 반영됨.
        memberGenreRepository.findAllByMemberWithGenre(me)
                .forEach(mg -> {
                    String pattern = mg.getGenre().getCategoryPattern();
                    if (pattern != null && !pattern.isBlank()) {
                        scoreMap.merge(pattern, GENRE_WEIGHT, Long::sum);
                    }
                });

        return scoreMap.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(TOP_CATEGORY_LIMIT)
                .map(Map.Entry::getKey)
                .toList();
    }
}
