package com.shelfeed.backend.domain.member.repository;

import com.shelfeed.backend.domain.member.entity.MemberUserIdSequence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface MemberUserIdSequenceRepository extends JpaRepository<MemberUserIdSequence, Integer> {

    /**
     * 시퀀스를 1 올리고, 올린 값을 이 커넥션의 {@code LAST_INSERT_ID()}에 실어 둔다.
     * MySQL의 표준적인 시퀀스 구현 방식이다 — 조회와 증가가 한 문장이라 경합이 생기지 않는다.
     *
     * @return 갱신된 행 수. 0이면 시퀀스 행이 아직 없다는 뜻.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE member_user_id_seq
            SET next_val = LAST_INSERT_ID(next_val + 1)
            WHERE id = 1
            """, nativeQuery = true)
    int bump();

    /**
     * 바로 앞의 {@link #bump()}가 실어 둔 값을 읽는다.
     *
     * <p>{@code LAST_INSERT_ID()}는 커넥션 단위 상태라 두 쿼리가 같은 트랜잭션 안에서
     * 실행되어야 한다. 호출부({@code MemberUserIdGenerator})가 트랜잭션을 보장한다.
     */
    @Query(value = "SELECT LAST_INSERT_ID()", nativeQuery = true)
    long lastIssued();

    /** 시퀀스 행이 없으면 현재 최대 회원 번호에서 시작하도록 만든다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT IGNORE INTO member_user_id_seq (id, next_val)
            SELECT 1, COALESCE(MAX(member_user_id), 0) FROM members
            """, nativeQuery = true)
    int createIfAbsent();

    /**
     * 시퀀스가 실제 회원 번호보다 뒤처져 있으면 끌어올린다.
     *
     * <p>뒤처진 시퀀스는 이미 쓰인 번호를 다시 발급해 회원가입을 통째로 막는다. 기동할 때마다
     * 한 번 보정해 두면 과거에 카운터가 유실된 환경도 배포만으로 스스로 복구된다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE member_user_id_seq
            SET next_val = GREATEST(next_val, (SELECT COALESCE(MAX(member_user_id), 0) FROM members))
            WHERE id = 1
            """, nativeQuery = true)
    int alignWithMembers();
}
