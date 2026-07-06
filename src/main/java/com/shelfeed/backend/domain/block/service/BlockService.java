package com.shelfeed.backend.domain.block.service;

import com.shelfeed.backend.domain.block.dto.response.BlockListResponse;
import com.shelfeed.backend.domain.block.dto.response.BlockMemberResponse;
import com.shelfeed.backend.domain.block.entity.Block;
import com.shelfeed.backend.domain.block.repository.BlockRepository;
import com.shelfeed.backend.domain.feed.repository.FeedRepository;
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

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BlockService {

    private final MemberRepository memberRepository;
    private final MemberLoader memberLoader;
    private final BlockRepository blockRepository;
    private final FollowRepository followRepository;
    private final FeedRepository feedRepository;

    //1. 차단
    @Transactional
    public void block(Long targetUserId, Long memberUserId) {
        //본인 차단 못함
        if (targetUserId.equals(memberUserId)) {
            throw new BusinessException(ErrorCode.SELF_BLOCK_NOT_ALLOWED);
        }

        Member blocker = memberLoader.getOrThrow(memberUserId);
        Member blocked = memberLoader.getOrThrow(targetUserId);
        //중복차단 방지
        if (blockRepository.existsByBlockerAndBlocked(blocker, blocked)) {
            throw new BusinessException(ErrorCode.ALREADY_BLOCKED);
        }
        blockRepository.save(Block.create(blocker, blocked));

        removeFollowIfExists(blocker, blocked);  // 내가 상대방 팔로우 해제
        removeFollowIfExists(blocked, blocker);  // 상대방이 나 팔로우 해제
        //상대방 감상을 내 피드에서 제거
        feedRepository.deleteByMemberAndReview_Member(blocker, blocked);
        //내 감상을 상대방 피드에서 제거
        feedRepository.deleteByMemberAndReview_Member(blocked, blocker);
    }

    //2. 차단 해제
    @Transactional
    public void unblock(Long targetUserId, Long memberUserId) {
        Member blocker = memberLoader.getOrThrow(memberUserId);
        Member blocked = memberLoader.getOrThrow(targetUserId);

        Block block = blockRepository.findByBlockerAndBlocked(blocker, blocked)
                .orElseThrow(() -> new BusinessException(ErrorCode.BLOCK_NOT_FOUND));

        blockRepository.delete(block);
    }

    //3. 차단 목록 조회
    public BlockListResponse getBlockList(Long memberUserId, Long cursor, int limit) {
        Member member = memberLoader.getOrThrow(memberUserId);

        List<Block> blocks = blockRepository.findBlocks(member, cursor, PageRequest.of(0, limit + 1));

        List<BlockMemberResponse> content = blocks.stream()
                .map(BlockMemberResponse::of)
                .toList();

        return BlockListResponse.of(content, limit);
    }

    /**
     * 두 회원 사이에 (어느 방향으로든) 차단 관계가 존재하는지 확인한다.
     * 감상/댓글/팔로우 등에서 차단 시 상호작용을 막는 양방향 검증에 사용.
     */
    public boolean isBlockedBetween(Member a, Member b) {
        return blockRepository.existsByBlockerAndBlocked(a, b)
                || blockRepository.existsByBlockerAndBlocked(b, a);
    }

    /**
     * 내가 차단했거나(blocked) 나를 차단한(blocking) 회원 ID 집합.
     * 검색/목록에서 차단 관계 회원을 일괄 필터링할 때 사용.
     */
    public Set<Long> blockedIdSet(Member member) {
        Set<Long> ids = new HashSet<>(blockRepository.findBlockedIds(member));
        ids.addAll(blockRepository.findBlockingIds(member));
        return ids;
    }

    //팔로우 관계라면 카운트 해제
    private void removeFollowIfExists(Member follower, Member followee) {
        // 벌크 DELETE의 실제 삭제 행 수로 감소를 게이팅 — 동시 요청 시 팔로잉/팔로워 수 이중 감소(드리프트) 방지
        int deleted = followRepository.deleteByFollowerAndFollowee(follower, followee);
        if (deleted > 0) {
            memberRepository.decreaseFollowingCount(follower.getMemberUserId());
            memberRepository.decreaseFollowerCount(followee.getMemberUserId());
        }
    }
}
