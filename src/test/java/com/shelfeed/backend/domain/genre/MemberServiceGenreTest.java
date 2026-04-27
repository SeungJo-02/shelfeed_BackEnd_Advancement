package com.shelfeed.backend.domain.genre;

import com.shelfeed.backend.domain.genre.entity.Genre;
import com.shelfeed.backend.domain.genre.repository.GenreRepository;
import com.shelfeed.backend.domain.genre.repository.MemberGenreRepository;
import com.shelfeed.backend.domain.member.dto.request.UpdateGenresRequest;
import com.shelfeed.backend.domain.member.dto.response.UpdateGenresResponse;
import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.member.repository.MemberRepository;
import com.shelfeed.backend.domain.member.service.MemberService;
import com.shelfeed.backend.global.common.exception.BusinessException;
import com.shelfeed.backend.global.common.exception.ErrorCode;
import com.shelfeed.backend.global.jwt.JwtProvider;
import com.shelfeed.backend.global.redis.RedisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("MemberService - 관심 장르 수정 단위 테스트")
class MemberServiceGenreTest {

    @Mock MemberRepository memberRepository;
    @Mock GenreRepository genreRepository;
    @Mock MemberGenreRepository memberGenreRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock RedisService redisService;
    @Mock JwtProvider jwtProvider;

    @InjectMocks MemberService memberService;

    private Member member;
    private Genre genre1;
    private Genre genre2;

    @BeforeEach
    void setUp() {
        member = Member.createLocal(1L, "test@test.com", "encoded", "테스터", "bio");
        genre1 = createGenre(1L, "소설");
        genre2 = createGenre(2L, "판타지");
    }

    private Genre createGenre(Long id, String name) {
        try {
            Constructor<Genre> ctor = Genre.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            Genre genre = ctor.newInstance();
            ReflectionTestUtils.setField(genre, "genreId", id);
            ReflectionTestUtils.setField(genre, "genreName", name);
            return genre;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private UpdateGenresRequest buildRequest(List<Long> genreIds) {
        UpdateGenresRequest request = new UpdateGenresRequest();
        ReflectionTestUtils.setField(request, "genreIds", genreIds);
        return request;
    }

    @Nested
    @DisplayName("updateMyGenres()")
    class UpdateMyGenres {

        @Test
        @DisplayName("정상 요청 시 기존 장르가 삭제되고 새 장르가 저장된 후 UpdateGenresResponse를 반환한다")
        void 정상_장르_업데이트_성공() {
            UpdateGenresRequest request = buildRequest(List.of(1L, 2L));
            given(memberRepository.findByMemberUserId(1L)).willReturn(Optional.of(member));
            given(genreRepository.findAllById(List.of(1L, 2L))).willReturn(List.of(genre1, genre2));

            UpdateGenresResponse response = memberService.updateMyGenres(1L, request);

            assertThat(response.getGenres()).hasSize(2);
            assertThat(response.getGenres()).extracting("genreId").containsExactlyInAnyOrder(1L, 2L);
            assertThat(response.getGenres()).extracting("name").containsExactlyInAnyOrder("소설", "판타지");
            verify(memberGenreRepository).deleteAllByMember(member);
            verify(memberGenreRepository).saveAll(anyList());
        }

        @Test
        @DisplayName("중복된 장르 ID가 포함되어도 중복이 제거되어 정상 처리된다")
        void 중복_장르ID_중복제거_성공() {
            // [1L, 1L, 2L] → distinct → [1L, 2L]
            UpdateGenresRequest request = buildRequest(List.of(1L, 1L, 2L));
            given(memberRepository.findByMemberUserId(1L)).willReturn(Optional.of(member));
            given(genreRepository.findAllById(List.of(1L, 2L))).willReturn(List.of(genre1, genre2));

            UpdateGenresResponse response = memberService.updateMyGenres(1L, request);

            assertThat(response.getGenres()).hasSize(2);
            verify(memberGenreRepository).deleteAllByMember(member);
            verify(memberGenreRepository).saveAll(anyList());
        }

        @Test
        @DisplayName("존재하지 않는 회원이면 MEMBER_NOT_FOUND 예외가 발생한다")
        void 존재하지않는_회원_예외() {
            UpdateGenresRequest request = buildRequest(List.of(1L));
            given(memberRepository.findByMemberUserId(99L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> memberService.updateMyGenres(99L, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.MEMBER_NOT_FOUND);

            verify(memberGenreRepository, never()).deleteAllByMember(any());
            verify(memberGenreRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("존재하지 않는 장르 ID가 포함되면 GENRE_NOT_FOUND 예외가 발생한다")
        void 존재하지않는_장르ID_예외() {
            // ID 999L은 DB에 없어서 findAllById 결과가 1개뿐 → size 불일치
            UpdateGenresRequest request = buildRequest(List.of(1L, 999L));
            given(memberRepository.findByMemberUserId(1L)).willReturn(Optional.of(member));
            given(genreRepository.findAllById(List.of(1L, 999L))).willReturn(List.of(genre1));

            assertThatThrownBy(() -> memberService.updateMyGenres(1L, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.GENRE_NOT_FOUND);

            verify(memberGenreRepository, never()).deleteAllByMember(any());
            verify(memberGenreRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("장르 1개만 전달해도 정상 처리된다")
        void 단일_장르_업데이트_성공() {
            UpdateGenresRequest request = buildRequest(List.of(1L));
            given(memberRepository.findByMemberUserId(1L)).willReturn(Optional.of(member));
            given(genreRepository.findAllById(List.of(1L))).willReturn(List.of(genre1));

            UpdateGenresResponse response = memberService.updateMyGenres(1L, request);

            assertThat(response.getGenres()).hasSize(1);
            assertThat(response.getGenres().get(0).getGenreId()).isEqualTo(1L);
            assertThat(response.getGenres().get(0).getName()).isEqualTo("소설");
        }
    }
}
