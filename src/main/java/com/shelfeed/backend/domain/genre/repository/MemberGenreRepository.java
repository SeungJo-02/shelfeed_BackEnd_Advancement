package com.shelfeed.backend.domain.genre.repository;

import com.shelfeed.backend.domain.genre.entity.MemberGenre;
import com.shelfeed.backend.domain.member.entity.Member;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;


//재 온보딩 시 유저의 선호장르 수정

public interface MemberGenreRepository extends JpaRepository<MemberGenre, Long> {
    void deleteAllByMember(Member member);

    @Query("""
    SELECT mg FROM MemberGenre mg JOIN FETCH mg.genre WHERE mg.member = :member
""")
    List<MemberGenre> findAllByMemberWithGenre(@Param("member") Member member);

}

