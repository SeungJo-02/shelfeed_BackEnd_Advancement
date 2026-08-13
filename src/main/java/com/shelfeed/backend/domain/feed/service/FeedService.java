package com.shelfeed.backend.domain.feed.service;

import com.shelfeed.backend.domain.feed.dto.response.FeedItemResponse;
import com.shelfeed.backend.domain.feed.dto.response.FeedListResponse;
import com.shelfeed.backend.domain.feed.entity.Feed;
import com.shelfeed.backend.domain.feed.repository.FeedRepository;
import com.shelfeed.backend.domain.follow.repository.FollowRepository;
import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.review.entity.Review;
import com.shelfeed.backend.domain.review.entity.ReviewTag;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FeedService {

    private final MemberLoader memberLoader;
    private final FeedRepository feedRepository;
    private final FollowRepository followRepository;
    private final ReviewRepository reviewRepository;
    private final ReviewLikeRepository reviewLikeRepository;
    private final ReviewTagRepository reviewTagRepository;
    private final RecommendationService recommendationService;

    // 팔로우 시 소급 피드 생성 시 가져올 최근 감상 최대 개수
    private static final int BACKFILL_LIMIT = 30;

    /**
     * 통합 피드의 유일한 정렬 기준: 최근에 작성된 감상이 위.
     *
     * <p>감상이 피드에 "들어온 시각"이 아니라 "작성된 시각"으로 줄을 세운다. 그래서
     * 누군가를 새로 팔로우해 과거 감상이 한꺼번에 들어와도 제자리(작성 시점)에 꽂힌다.
     *
     * <p>createdAt이 같으면 reviewId 내림차순으로 갈라 순서를 확정한다 — 순서가 흔들리면
     * 커서 페이지네이션에서 항목이 누락되거나 중복된다. 아직 영속화되지 않아 값이 없는
     * 감상은 맨 뒤로 보낸다.
     */
    static final Comparator<Review> NEWEST_FIRST =
            Comparator.comparing(Review::getCreatedAt,
                            Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(Review::getReviewId,
                            Comparator.nullsLast(Comparator.reverseOrder()));

    /**
     * 감상을 작성자의 모든 팔로워 피드에 일괄 생성한다.
     * (공개 감상이 게시될 때 호출)
     */
    @Transactional
    public void publishToFollowers(Member reviewer, Review review) {
        List<Feed> feeds = followRepository.findAllFollowersWithMember(reviewer).stream()
                .map(follow -> Feed.create(follow.getFollower(), review))
                .toList();
        if (!feeds.isEmpty()) feedRepository.saveAll(feeds);
    }

    /**
     * 감상이 비공개로 전환되거나 삭제될 때 모든 팔로워 피드에서 제거한다.
     */
    @Transactional
    public void removeByReview(Review review) {
        feedRepository.deleteByReview(review);
    }

    /**
     * 팔로우 시 대상(followee)의 최근 감상을 팔로워 피드에 소급 생성한다.
     * (findUserReviews는 PUBLISHED+PUBLIC 감상만 반환)
     *
     * <p>여기서 생성된 피드 행은 "지금" 만들어지지만 노출 순서는 감상 작성 시각을 따르므로,
     * 오래된 감상이 피드 최상단을 차지하지 않는다. ({@link #NEWEST_FIRST} 참고)
     */
    @Transactional
    public void backfillOnFollow(Member follower, Member followee) {
        List<Review> recentReviews =
                reviewRepository.findUserReviews(followee, null, PageRequest.of(0, BACKFILL_LIMIT));
        if (!recentReviews.isEmpty()) {
            feedRepository.saveAll(recentReviews.stream()
                    .map(r -> Feed.create(follower, r))
                    .toList());
        }
    }

    /**
     * 언팔로우 시 대상(followee)의 감상을 팔로워 피드에서 제거한다.
     */
    @Transactional
    public void removeOnUnfollow(Member follower, Member followee) {
        feedRepository.deleteByMemberAndReview_Member(follower, followee);
    }

    /**
     * 통합 피드를 조회한다. 팔로우한 사람의 감상과 추천 감상을 한 줄로 합쳐 최신순으로 돌려준다.
     *
     * <p>두 출처에서 각각 커서 조건을 적용해 가져온 뒤 메모리에서 병합한다. 같은 커서 규약을
     * 쓰기 때문에 두 목록 모두 "커서보다 과거"만 담고 있고, 병합 후 잘라내도 다음 페이지에서
     * 빠지는 감상이 없다. 두 출처에 동시에 걸린 감상은 한 번만 노출한다.
     *
     * @param cursorCreatedAt 직전 페이지 마지막 감상의 작성 시각 (첫 페이지면 null)
     * @param cursorId        직전 페이지 마지막 감상의 ID (첫 페이지면 null)
     */
    public FeedListResponse getFeed(Long memberUserId, LocalDateTime cursorCreatedAt,
                                    Long cursorId, int limit) {
        if (limit <= 0) throw new BusinessException(ErrorCode.INVALID_INPUT);
        // 커서는 (시각, ID) 한 쌍으로만 의미가 있다. 한쪽만 오면 경계 판정이 불가능하다.
        if ((cursorCreatedAt == null) != (cursorId == null)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        Member member = memberLoader.getOrThrow(memberUserId);

        // hasNext 판정을 위해 각 출처에서 limit + 1건씩 당겨온다.
        int fetchSize = limit + 1;

        List<Review> following = feedRepository
                .findFeedWithDetails(member, cursorCreatedAt, cursorId, PageRequest.of(0, fetchSize))
                .stream()
                .map(Feed::getReview)
                .toList();

        List<Review> recommended =
                recommendationService.findCandidates(member, cursorCreatedAt, cursorId, fetchSize);

        List<Review> merged = mergeNewestFirst(following, recommended, fetchSize);
        if (merged.isEmpty()) {
            return FeedListResponse.of(Collections.emptyList(), limit);
        }

        List<Long> reviewIds = merged.stream().map(Review::getReviewId).toList();

        Set<Long> likedReviewIds =
                reviewLikeRepository.findLikedReviewIds(reviewIds, member.getMemberId());

        List<ReviewTag> allTags = reviewTagRepository.findByReviewIdIn(reviewIds);
        Map<Long, List<String>> tagMap = allTags.stream()
                .collect(Collectors.groupingBy(
                        rt -> rt.getReview().getReviewId(),
                        Collectors.mapping(rt -> rt.getTag().getTagName(), Collectors.toList())));

        List<FeedItemResponse> content = merged.stream()
                .map(review -> FeedItemResponse.of(
                        review,
                        likedReviewIds.contains(review.getReviewId()),
                        tagMap.getOrDefault(review.getReviewId(), Collections.emptyList())))
                .toList();

        return FeedListResponse.of(content, limit);
    }

    /**
     * 두 출처를 합쳐 중복을 걷어내고 최신순으로 정렬한 뒤 {@code size}건까지 자른다.
     * 같은 감상이 양쪽에 있으면 먼저 넣은 쪽(팔로잉)을 남긴다.
     */
    private List<Review> mergeNewestFirst(List<Review> following, List<Review> recommended, int size) {
        Map<Long, Review> byReviewId = new LinkedHashMap<>();
        following.forEach(r -> byReviewId.putIfAbsent(r.getReviewId(), r));
        recommended.forEach(r -> byReviewId.putIfAbsent(r.getReviewId(), r));

        List<Review> merged = new ArrayList<>(byReviewId.values());
        merged.sort(NEWEST_FIRST);
        return merged.size() > size ? merged.subList(0, size) : merged;
    }
}
