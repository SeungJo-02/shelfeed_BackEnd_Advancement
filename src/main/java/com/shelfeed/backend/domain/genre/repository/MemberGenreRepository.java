package com.shelfeed.backend.domain.genre.repository;

import com.shelfeed.backend.domain.genre.entity.MemberGenre;
import com.shelfeed.backend.domain.member.entity.Member;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;


//재 온보딩 시 유저의 선호장르 수정

public interface MemberGenreRepository extends JpaRepository<MemberGenre, Long> {
    // 장르 전체 교체 시 delete가 saveAll INSERT보다 먼저 DB에 반영되도록 flush 강제.
    // (기본 derived delete는 영속성 컨텍스트에 보류되어 INSERT가 먼저 flush되면
    //  member_genres (member_id, genre_id) 복합 unique 위반 → 409가 발생함)
    // clearAutomatically는 쓰지 않는다 — completeOnboarding은 이 delete 이후에도 member를
    // 더 변경(completeOnboarding())하는데, clear로 member가 detach되면 그 변경이 DB에 반영되지
    // 않는다. 409 해소엔 flush 순서만 보장하면 충분하다.
    @Modifying(flushAutomatically = true)
    @Query("delete from MemberGenre mg where mg.member = :member")
    void deleteAllByMember(@Param("member") Member member);

    @Query("""
    SELECT mg FROM MemberGenre mg JOIN FETCH mg.genre WHERE mg.member = :member
""")
    List<MemberGenre> findAllByMemberWithGenre(@Param("member") Member member);

}

