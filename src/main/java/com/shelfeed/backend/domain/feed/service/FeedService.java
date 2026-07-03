package com.shelfeed.backend.domain.feed.service;

import com.shelfeed.backend.domain.feed.dto.response.FeedItemResponse;
import com.shelfeed.backend.domain.feed.dto.response.FeedListResponse;
import com.shelfeed.backend.domain.feed.entity.Feed;
import com.shelfeed.backend.domain.feed.repository.FeedRepository;
import com.shelfeed.backend.domain.follow.entity.Follow;
import com.shelfeed.backend.domain.follow.repository.FollowRepository;
import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.review.entity.Review;
import com.shelfeed.backend.domain.review.entity.ReviewTag;
import com.shelfeed.backend.domain.review.repository.ReviewLikeRepository;
import com.shelfeed.backend.domain.review.repository.ReviewRepository;
import com.shelfeed.backend.domain.review.repository.ReviewTagRepository;
import com.shelfeed.backend.global.common.helper.MemberLoader;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
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

    // 팔로우 시 소급 피드 생성 시 가져올 최근 감상 최대 개수
    private static final int BACKFILL_LIMIT = 30;

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

    //피드 조회
    public FeedListResponse getFollowingFeed(Long memberUserId, Long cursor, int limit) {
        Member member = memberLoader.getOrThrow(memberUserId);
        //피드페이지
        //패치 조인으로 피드랑 감상 가져오기
        List<Feed> feeds = feedRepository.findFeedWithDetails(member, cursor, PageRequest.of(0, limit + 1));
        // 리뷰 아이디만 뽑기(in절에 사용하려고)
        List<Long> reviewIds = feeds.stream().map(feed -> feed.getReview().getReviewId()).toList();
        //in절로 좋아요 조회
        Set<Long> likedReviewIds = reviewLikeRepository.findLikedReviewIds(reviewIds, memberUserId);
        //태그 목록 in 절
        List<ReviewTag> allTags = reviewTagRepository.findByReviewIdIn(reviewIds);
        Map<Long, List<String>> tagMap = allTags.stream()
                .collect(Collectors.groupingBy(
                        rt -> rt.getReview().getReviewId(),
                        Collectors.mapping(rt -> rt.getTag().getTagName(), Collectors.toList())
                ));
        //최종
        List<FeedItemResponse> content = feeds.stream().map(feed -> {
            Long reviewId = feed.getReview().getReviewId();
            boolean isLiked = likedReviewIds.contains(reviewId);
            List<String> tags = tagMap.getOrDefault(reviewId, Collections.emptyList());

            return FeedItemResponse.of(feed, isLiked, tags);
        }).toList();

        return FeedListResponse.of(content, limit);
    }
}
