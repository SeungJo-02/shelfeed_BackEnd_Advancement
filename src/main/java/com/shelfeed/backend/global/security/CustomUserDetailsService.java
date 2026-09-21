package com.shelfeed.backend.global.security;
// 사용자 조회해서 CustomUserDetails로 바꿔주는 로직
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.member.repository.MemberRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * [실험 H] 인증 단계의 회원 조회를 로컬 캐시로 받는다.
 *
 * <p>JWT를 들고 오는 모든 요청이 여기서 {@code SELECT members}를 한 번 돌았다. 감상 상세를
 * 캐시로 덮어도 이 한 건이 남아 있으면 GET은 여전히 커넥션을 빌리고, 풀이 8개인 상태에서는
 * 그것만으로 대기열이 찬다. 토큰이 이미 서명으로 신원을 증명하므로, 회원 행은 권한·상태
 * 확인용이다. 60초 정도 늦게 반영돼도 되는 값이라 로컬 캐시로 받는다.
 *
 * <p>{@code @Transactional(readOnly = true)}를 뗐다. 트랜잭션은 시작하는 순간 커넥션을
 * 빌리므로, 캐시에 적중해도 붙어 있으면 커넥션을 빌린다. 적재는 리포지토리 호출 한 건이라
 * 그쪽에서 알아서 트랜잭션이 걸린다.
 *
 * <p>캐시된 Member는 영속성 컨텍스트에서 떨어진(detached) 객체다. Member 엔티티에는 지연
 * 로딩 연관이 하나도 없고, 꺼내 쓰는 쪽도 memberUserId·nickname 같은 단순 필드만 읽으므로
 * 문제되지 않는다. 다만 이 객체를 그대로 save하면 안 된다.
 *
 * <p>상태·권한·비밀번호가 바뀌면 {@link #evict(Long)}로 즉시 버린다. 탈퇴·정지 회원이 최대
 * 60초 동안 살아 있는 일을 막는다.
 */
@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final MemberRepository memberRepository;

    private final Cache<Long, Member> principalCache = Caffeine.newBuilder()
            .maximumSize(20_000)
            .expireAfterWrite(60, TimeUnit.SECONDS)
            .build();

    public CustomUserDetailsService(MemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String memberUserId) throws UsernameNotFoundException {
        Long id = Long.parseLong(memberUserId);
        // 로더가 null을 돌려주면 Caffeine은 아무것도 담지 않는다 — 없는 회원을 캐싱하지 않는다.
        Member member = principalCache.get(id, key -> memberRepository.findByMemberUserId(key).orElse(null));
        if (member == null) {
            throw new UsernameNotFoundException("회원을 찾을 수 없습니다: " + memberUserId);
        }
        return new CustomUserDetails(member);
    }

    /** 회원 상태/권한/비밀번호가 바뀐 직후 호출한다. */
    public void evict(Long memberUserId) {
        if (memberUserId != null) {
            principalCache.invalidate(memberUserId);
        }
    }
}
