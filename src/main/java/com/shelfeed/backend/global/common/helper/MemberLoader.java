package com.shelfeed.backend.global.common.helper;

import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.member.repository.MemberRepository;
import com.shelfeed.backend.global.common.exception.BusinessException;
import com.shelfeed.backend.global.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

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
