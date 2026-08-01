package com.shelfeed.backend.global.common.helper;

import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.member.repository.MemberRepository;
import com.shelfeed.backend.global.common.exception.BusinessException;
import com.shelfeed.backend.global.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class MemberLoader {

    private final MemberRepository memberRepository;

    public Member getOrThrow(Long memberUserId) {
        return memberRepository.findByMemberUserId(memberUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
    }

    /**
     * PK(memberId)로 조회한다. 서비스 경계를 넘는 참조가 ID로 바뀌면서, 저장된 PK를 다시 회원으로
     * 되돌려야 하는 자리에서 쓴다.
     */
    public Member getByMemberIdOrThrow(Long memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
    }

    /**
     * 여러 PK를 한 번의 IN 쿼리로 조회해 memberId → Member 맵으로 돌려준다.
     * 조인을 대체하는 배치 조립용이라, 없는 회원(탈퇴 등)은 예외 없이 결과에서 빠진다.
     */
    public Map<Long, Member> findAllByMemberIds(Collection<Long> memberIds) {
        if (memberIds.isEmpty()) return Map.of();
        return memberRepository.findAllById(memberIds).stream()
                .collect(Collectors.toMap(Member::getMemberId, m -> m));
    }

    // 여러 ID를 한 번의 IN 쿼리로 조회 — 하나라도 없으면 MEMBER_NOT_FOUND
    public Map<Long, Member> getOrThrowAll(List<Long> ids) {
        Map<Long, Member> map = memberRepository.findByMemberUserIdIn(ids).stream()
                .collect(Collectors.toMap(Member::getMemberUserId, m -> m));
        for (Long id : ids) {
            if (!map.containsKey(id)) throw new BusinessException(ErrorCode.MEMBER_NOT_FOUND);
        }
        return map;
    }
}
