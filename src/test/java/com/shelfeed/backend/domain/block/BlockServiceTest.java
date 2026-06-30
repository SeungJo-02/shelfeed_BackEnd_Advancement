package com.shelfeed.backend.domain.block;

import com.shelfeed.backend.domain.block.repository.BlockRepository;
import com.shelfeed.backend.domain.block.service.BlockService;
import com.shelfeed.backend.domain.feed.repository.FeedRepository;
import com.shelfeed.backend.domain.follow.repository.FollowRepository;
import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.member.repository.MemberRepository;
import com.shelfeed.backend.global.common.helper.MemberLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("BlockService 단위 테스트")
class BlockServiceTest {

    @Mock MemberRepository memberRepository;
    @Mock MemberLoader memberLoader;
    @Mock BlockRepository blockRepository;
    @Mock FollowRepository followRepository;
    @Mock FeedRepository feedRepository;

    @InjectMocks BlockService blockService;

    private Member a;
    private Member b;

    @BeforeEach
    void setUp() {
        a = Member.createLocal(1L, "a@test.com", "encoded", "회원A", "bio");
        b = Member.createLocal(2L, "b@test.com", "encoded", "회원B", "bio");
    }

    // ────────────────────────────────────────────────────────
    // isBlockedBetween() — 양방향 차단 검증
    // ────────────────────────────────────────────────────────
    @Nested
    @DisplayName("isBlockedBetween (양방향 차단 확인)")
    class IsBlockedBetween {

        @Test
        @DisplayName("a가 b를 차단했으면 true (반대 방향은 조회하지 않음)")
        void 정방향_차단_true() {
            given(blockRepository.existsByBlockerAndBlocked(a, b)).willReturn(true);

            assertThat(blockService.isBlockedBetween(a, b)).isTrue();
            verify(blockRepository, never()).existsByBlockerAndBlocked(b, a);
        }

        @Test
        @DisplayName("b가 a를 차단했으면 true")
        void 역방향_차단_true() {
            given(blockRepository.existsByBlockerAndBlocked(a, b)).willReturn(false);
            given(blockRepository.existsByBlockerAndBlocked(b, a)).willReturn(true);

            assertThat(blockService.isBlockedBetween(a, b)).isTrue();
        }

        @Test
        @DisplayName("어느 방향으로도 차단이 없으면 false")
        void 차단_없음_false() {
            given(blockRepository.existsByBlockerAndBlocked(a, b)).willReturn(false);
            given(blockRepository.existsByBlockerAndBlocked(b, a)).willReturn(false);

            assertThat(blockService.isBlockedBetween(a, b)).isFalse();
        }
    }

    // ────────────────────────────────────────────────────────
    // blockedIdSet() — 차단/피차단 ID 합집합
    // ────────────────────────────────────────────────────────
    @Nested
    @DisplayName("blockedIdSet (차단/피차단 ID 합집합)")
    class BlockedIdSet {

        @Test
        @DisplayName("내가 차단한 ID와 나를 차단한 ID를 합집합(중복 제거)으로 반환한다")
        void 차단_피차단_합집합() {
            given(blockRepository.findBlockedIds(a)).willReturn(Set.of(2L, 3L));
            given(blockRepository.findBlockingIds(a)).willReturn(Set.of(3L, 4L));

            Set<Long> result = blockService.blockedIdSet(a);

            assertThat(result).containsExactlyInAnyOrder(2L, 3L, 4L);
        }

        @Test
        @DisplayName("차단 관계가 전혀 없으면 빈 집합을 반환한다")
        void 차단_없음_빈집합() {
            given(blockRepository.findBlockedIds(a)).willReturn(Set.of());
            given(blockRepository.findBlockingIds(a)).willReturn(Set.of());

            assertThat(blockService.blockedIdSet(a)).isEmpty();
        }
    }
}
