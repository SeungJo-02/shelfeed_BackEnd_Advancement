package com.shelfeed.backend.domain.block.repository;


import com.shelfeed.backend.domain.block.entity.Block;
import com.shelfeed.backend.domain.member.entity.Member;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface BlockRepository extends JpaRepository<Block, Long> {
    //차단한 사람 입장에서 차단인을 차단했는가
    boolean existsByBlockerAndBlocked(Member blocker, Member blocked);
    //차단한 사람 입장에서 차단인 사람 찾기(유저랑 차단인 간의 관계)
    Optional<Block> findByBlockerAndBlocked(Member blocker, Member blocked);

    // 탈퇴 처리용: 해당 멤버가 포함된 모든 차단 관계 제거
    @Modifying
    @Query("DELETE FROM Block b WHERE b.blocker = :member OR b.blocked = :member")
    void deleteAllByMember(@Param("member") Member member);

    //차단 목록 페이지네이션
    @Query("""
            SELECT b FROM Block b
            WHERE b.blocker = :member
            AND (:cursor IS NULL OR b.blockId < :cursor)
            ORDER BY b.blockId DESC
    """)
    List<Block> findBlocks(@Param("member") Member member,
                           @Param("cursor") Long cursor,
                           Pageable pageable);

    @Query("SELECT b.blocked.memberUserId FROM Block b WHERE b.blocker = :member")
    Set<Long> findBlockedIds(@Param("member") Member member);

    @Query("SELECT b.blocker.memberUserId FROM Block b WHERE b.blocked = :member")
    Set<Long> findBlockingIds(@Param("member") Member member);
}
