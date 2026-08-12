package com.shelfeed.backend.domain.member.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 공개 회원 번호({@code members.member_user_id}) 발급용 시퀀스. 항상 한 행만 존재한다.
 *
 * <p>예전에는 이 번호를 Redis {@code INCR}로 발급했는데, Redis가 영속화되지 않아 재시작·eviction으로
 * 카운터가 사라지면 이미 쓰인 번호를 다시 내주었다. 그러면 회원 저장이 unique 제약에 걸려
 * 회원가입 전체가 409로 막힌다. 회원의 신원을 발급하는 일은 회원 데이터와 같은 트랜잭션·같은
 * 저장소에서 일어나야 한다는 판단으로 DB로 옮겼다.
 *
 * <p>테이블 자체를 쓰는 이유: MySQL은 테이블당 AUTO_INCREMENT를 하나만 둘 수 있어
 * {@code members}의 PK가 이미 그 자리를 쓰고 있다.
 */
@Entity
@Table(name = "member_user_id_seq")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberUserIdSequence {

    /** 단일 행 고정 키. */
    public static final int SINGLETON_ID = 1;

    @Id
    @Column(name = "id")
    private Integer id;

    /** 마지막으로 발급한 번호. 다음 발급 값은 이 값 + 1이다. */
    @Column(name = "next_val", nullable = false)
    private Long nextVal;
}
