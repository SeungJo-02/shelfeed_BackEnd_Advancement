package com.shelfeed.backend.domain.follow.service;

import com.shelfeed.backend.domain.block.repository.BlockRepository;
import com.shelfeed.backend.domain.feed.entity.Feed;
import com.shelfeed.backend.domain.feed.repository.FeedRepository;
import com.shelfeed.backend.domain.notification.entity.Notification;
import com.shelfeed.backend.domain.notification.enums.NotificationType;
import com.shelfeed.backend.domain.notification.repository.NotificationRepository;
import com.shelfeed.backend.domain.review.entity.Review;
import com.shelfeed.backend.domain.review.repository.ReviewRepository;
import org.springframework.data.domain.PageRequest;
import com.shelfeed.backend.domain.follow.dto.response.FollowListResponse;
import com.shelfeed.backend.domain.follow.dto.response.FollowMemberResponse;
import com.shelfeed.backend.domain.follow.dto.response.FollowResponse;
import com.shelfeed.backend.domain.follow.dto.response.UnfollowResponse;
import com.shelfeed.backend.domain.follow.entity.Follow;
import com.shelfeed.backend.domain.follow.repository.FollowRepository;
import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.member.repository.MemberRepository;
import com.shelfeed.backend.global.common.exception.BusinessException;
import com.shelfeed.backend.global.common.exception.ErrorCode;
import com.shelfeed.backend.global.common.helper.MemberLoader;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Function;


@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FollowService {

    private final MemberRepository memberRepository;
    private final MemberLoader memberLoader;
    private final FollowRepository followRepository;
    private final FeedRepository feedRepository;
    private final ReviewRepository reviewRepository;
    private final NotificationRepository notificationRepository;
    private final BlockRepository blockRepository;

    //1. 팔로우
    @Transactional
    public FollowResponse follow(Long targetUserId, Long memberUserId){
        //나 자신 팔로우 안됨
        if (targetUserId.equals(memberUserId)){
            throw new BusinessException(ErrorCode.CANNOT_FOLLOW_SELF);
        }
        Member follower = memberLoader.getOrThrow(memberUserId);
        Member followee = memberLoader.getOrThrow(targetUserId);
        // 차단 관계 확인 (양방향)
        if (blockRepository.existsByBlockerAndBlocked(follower, followee) ||
            blockRepository.existsByBlockerAndBlocked(followee, follower)) {
            throw new BusinessException(ErrorCode.BLOCKED_USER);
        }
        //중복 팔로우 방지
        if (followRepository.existsByFollowerAndFollowee(follower, followee)){
            throw new BusinessException(ErrorCode.ALREADY_FOLLOWING);
        }

        Follow follow = followRepository.save(Follow.create(follower,followee));
        // 카운트 업데이트
        memberRepository.increaseFollowingCount(follower.getMemberUserId());
        memberRepository.increaseFollowerCount(followee.getMemberUserId());
        notificationRepository.save(Notification.createUserNotification(
                followee, follower, NotificationType.FOLLOW, follow.getFollowId()));
        // 팔로우의 최근 PUBLISHED+PUBLIC 감상 최대 30개 소급 피드 생성
        List<Review> recentReviews = reviewRepository.findUserReviews(followee, null, PageRequest.of(0, 30));
        if (!recentReviews.isEmpty()) {
            feedRepository.saveAll(recentReviews.stream().map(r -> Feed.create(follower, r)).toList());
        }
        return FollowResponse.of(follow,follower);
    }
    //2.언팔로우
    @Transactional
    public UnfollowResponse unfollow(Long targetUserId, Long memberUserId){
        Member follower = memberLoader.getOrThrow(memberUserId);
        Member followee = memberLoader.getOrThrow(targetUserId);
        //삭제 대상 조회
        Follow follow = followRepository.findByFollowerAndFollowee(follower,followee)
                .orElseThrow(() -> new BusinessException(ErrorCode.FOLLOW_NOT_FOUND));
        followRepository.delete(follow);
        // 카운트 업데이트
        memberRepository.decreaseFollowingCount(follower.getMemberUserId());
        memberRepository.decreaseFollowerCount(followee.getMemberUserId());
        //엔팔한 멤버의 피드 내 피드화면에서 제거
        feedRepository.deleteByMemberAndReview_Member(follower,followee);

        return UnfollowResponse.of(followee,follower);
    }
    //3.팔로워 목록
    public FollowListResponse getFollowers(Long targetUserId, Long cursor, int limit, Long memberUserId) {
        Member target = memberLoader.getOrThrow(targetUserId);
        //사용자 정보 1번만 조회
        Member me = (memberUserId != null) ? memberLoader.getOrThrow(memberUserId) : null;
        //팔로워 목록 조회(패치조인)
        List<Follow> follows = followRepository.findFollowersWithMember(target, cursor, PageRequest.of(0, limit + 1));
        return buildFollowList(follows, Follow::getFollower, me, limit);
    }

    //4.팔로잉 목록
    public FollowListResponse getFollowings(Long targetUserId, Long cursor, int limit, Long memberUserId) {
        Member target = memberLoader.getOrThrow(targetUserId);
        // 사용자 정보 1번만 조회
        Member me = (memberUserId != null) ? memberLoader.getOrThrow(memberUserId) : null;
        // 팔로잉 목록 조회 (패치조인)
        List<Follow> follows = followRepository.findFollowingsWithMember(target, cursor, PageRequest.of(0, limit + 1));
        return buildFollowList(follows, Follow::getFollowee, me, limit);
    }

    private FollowListResponse buildFollowList(List<Follow> follows, Function<Follow, Member> memberExtractor, Member me, int limit) {
        List<Member> candidates = follows.stream().map(memberExtractor).toList();
        Set<Long> followingIds = Collections.emptySet();
        Set<Long> followedByIds = Collections.emptySet();

        if (me != null && !candidates.isEmpty()) {
            followingIds = followRepository.findFollowingIds(me, candidates);
            followedByIds = followRepository.findFollowedByIds(me, candidates);
        }

        final Set<Long> finalFollowingIds = followingIds;
        final Set<Long> finalFollowedByIds = followedByIds;

        List<FollowMemberResponse> content = follows.stream().map(follow -> {
            Member member = memberExtractor.apply(follow);
            Long memberId = member.getMemberUserId();
            return FollowMemberResponse.of(member, finalFollowingIds.contains(memberId), finalFollowedByIds.contains(memberId));
        }).toList();

        return FollowListResponse.of(content, limit);
    }



}
