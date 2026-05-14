package com.shelfeed.backend.domain.feed.service;

import com.shelfeed.backend.domain.feed.dto.response.RecommendFeedResponse;
import com.shelfeed.backend.domain.feed.dto.response.RecommendItemResponse;
import com.shelfeed.backend.domain.genre.repository.MemberGenreRepository;
import com.shelfeed.backend.domain.library.repository.LibraryRepository;
import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.review.entity.Review;
import com.shelfeed.backend.domain.review.repository.ReviewLikeRepository;
import com.shelfeed.backend.domain.review.repository.ReviewRepository;
import com.shelfeed.backend.domain.review.repository.ReviewTagRepository;
import com.shelfeed.backend.global.common.exception.BusinessException;
import com.shelfeed.backend.global.common.exception.ErrorCode;
import com.shelfeed.backend.global.common.helper.MemberLoader;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecommendationService {

    private final MemberLoader memberLoader;
    private final LibraryRepository libraryRepository;
    private final ReviewRepository reviewRepository;
    private final ReviewLikeRepository reviewLikeRepository;
    private final ReviewTagRepository reviewTagRepository;
    private final MemberGenreRepository memberGenreRepository;

    private static final int TOP_CATEGORY_LIMIT = 5;
    private static final long LIBRARY_WEIGHT    = 3L;
    private static final long GENRE_WEIGHT      = 1L;
    private static final long RECENT_DAYS       = 30L;

    public RecommendFeedResponse getRecommendedFeed(Long memberUserId, Integer cursorLike,
                                                    Long cursorId, int limit) {
        if (limit <= 0) throw new BusinessException(ErrorCode.INVALID_INPUT);
        if ((cursorLike == null) != (cursorId == null)) throw new BusinessException(ErrorCode.INVALID_INPUT);

        Member me = memberLoader.getOrThrow(memberUserId);
        List<String> topCategories = buildTopCategories(me);
        List<Review> reviews;
        String recommendType;

        if (!topCategories.isEmpty()) {
            reviews = reviewRepository.findRecommendedByGenres(
                    topCategories, me, cursorLike, cursorId, PageRequest.of(0, limit + 1));

            // 결과가 절반 미만이면 소셜 기반으로 보충
            if (reviews.size() < limit / 2) {
                List<Review> social = reviewRepository.findRecommendedByFolloweeLibrary(
                        me, cursorLike, cursorId, PageRequest.of(0, limit + 1));
                Set<Long> seen = reviews.stream()
                        .map(Review::getReviewId).collect(Collectors.toSet());
                List<Review> merged = new ArrayList<>(reviews);
                social.stream()
                        .filter(r -> !seen.contains(r.getReviewId()))
                        .forEach(merged::add);
                merged.sort(Comparator.comparingInt(Review::getLikeCount)
                        .thenComparing(Review::getReviewId)
                        .reversed());
                if (merged.size() > limit + 1) merged = merged.subList(0, limit + 1);
                reviews = merged;
                recommendType = "MIXED";
            } else {
                recommendType = "CONTENT_BASED";
            }
        } else {
            // Cold-start: 최근 30일 인기 감상카드
            reviews = reviewRepository.findPopularRecent(
                    LocalDateTime.now().minusDays(RECENT_DAYS),
                    me, cursorLike, cursorId, PageRequest.of(0, limit + 1));
            recommendType = "POPULAR";
        }

        if (reviews.isEmpty()) {
            return RecommendFeedResponse.of(Collections.emptyList(), limit, recommendType);
        }

        List<Long> reviewIds = reviews.stream().map(Review::getReviewId).toList();

        Set<Long> likedIds = reviewLikeRepository.findLikedReviewIds(reviewIds, memberUserId);

        Map<Long, List<String>> tagMap = reviewTagRepository.findByReviewIdIn(reviewIds).stream()
                .collect(Collectors.groupingBy(
                        rt -> rt.getReview().getReviewId(),
                        Collectors.mapping(rt -> rt.getTag().getTagName(), Collectors.toList())));

        List<RecommendItemResponse> content = reviews.stream()
                .map(r -> RecommendItemResponse.of(
                        r,
                        likedIds.contains(r.getReviewId()),
                        tagMap.getOrDefault(r.getReviewId(), Collections.emptyList())))
                .toList();

        return RecommendFeedResponse.of(content, limit, recommendType);
    }

    // 서재 장르(가중치 3) + 온보딩 장르(가중치 1) 합산 → Top-5
    private List<String> buildTopCategories(Member me) {
        Map<String, Long> scoreMap = new LinkedHashMap<>();

        libraryRepository.findTopGenresByMember(me, PageRequest.of(0, 20))
                .forEach(row -> scoreMap.merge(
                        (String) row[0], (Long) row[1] * LIBRARY_WEIGHT, Long::sum));

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
