package com.shelfeed.backend.domain.follow.service;

import com.shelfeed.backend.domain.block.service.BlockService;
import com.shelfeed.backend.domain.feed.service.FeedService;
import com.shelfeed.backend.domain.notification.service.NotificationService;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;


@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FollowService {

    private final MemberRepository memberRepository;
    private final MemberLoader memberLoader;
    private final FollowRepository followRepository;
    private final BlockService blockService;
    private final FeedService feedService;
    private final NotificationService notificationService;

    //1. 팔로우
    @Transactional
    public FollowResponse follow(Long targetUserId, Long memberUserId){
        //나 자신 팔로우 안됨
        if (targetUserId.equals(memberUserId)){
            throw new BusinessException(ErrorCode.CANNOT_FOLLOW_SELF);
        }
        Map<Long, Member> members = memberLoader.getOrThrowAll(List.of(memberUserId, targetUserId));
        Member follower = members.get(memberUserId);
        Member followee = members.get(targetUserId);
        // 차단 관계 확인 (양방향)
        if (blockService.isBlockedBetween(follower, followee)) {
            throw new BusinessException(ErrorCode.BLOCKED_USER);
        }
        //중복 팔로우 방지
        if (followRepository.existsByFollowerAndFollowee(follower, followee)){
            throw new BusinessException(ErrorCode.ALREADY_FOLLOWING);
        }

        // 동시 요청 경쟁: exists 동시 통과 후 unique 위반을 409로 멱등 변환 (saveAndFlush로 즉시 검출)
        Follow follow;
        try {
            follow = followRepository.saveAndFlush(Follow.create(follower, followee));
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.ALREADY_FOLLOWING);
        }
        // 카운트 업데이트
        memberRepository.increaseFollowingCount(follower.getMemberUserId());
        memberRepository.increaseFollowerCount(followee.getMemberUserId());
        notificationService.notifyFollow(followee, follower, follow.getFollowId());
        // 팔로우 대상의 최근 감상을 팔로워 피드에 소급 생성
        feedService.backfillOnFollow(follower, followee);
        // clearAutomatically=true 로 영속성 컨텍스트가 비워졌으므로 DB에서 최신 카운트를 재조회
        Member freshFollower = memberLoader.getOrThrow(memberUserId);
        Member freshFollowee = memberLoader.getOrThrow(targetUserId);
        return FollowResponse.of(follow, freshFollower, freshFollowee);
    }
    //2.언팔로우
    @Transactional
    public UnfollowResponse unfollow(Long targetUserId, Long memberUserId){
        Map<Long, Member> members = memberLoader.getOrThrowAll(List.of(memberUserId, targetUserId));
        Member follower = members.get(memberUserId);
        Member followee = members.get(targetUserId);
        // 벌크 DELETE의 실제 삭제 행 수로 감소를 게이팅 — 동시 중복 언팔로우 시 팔로잉/팔로워 수 이중 감소(드리프트) 방지
        int deleted = followRepository.deleteByFollowerAndFollowee(follower, followee);
        if (deleted == 0) {
            throw new BusinessException(ErrorCode.FOLLOW_NOT_FOUND);
        }
        // 카운트 업데이트 (실제 삭제된 경우에만)
        memberRepository.decreaseFollowingCount(follower.getMemberUserId());
        memberRepository.decreaseFollowerCount(followee.getMemberUserId());
        //언팔한 멤버의 감상을 내 피드에서 제거
        feedService.removeOnUnfollow(follower, followee);
        // clearAutomatically=true 로 영속성 컨텍스트가 비워졌으므로 DB에서 최신 카운트를 재조회
        Member freshFollower = memberLoader.getOrThrow(memberUserId);
        Member freshFollowee = memberLoader.getOrThrow(targetUserId);
        return UnfollowResponse.of(freshFollowee, freshFollower);
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
