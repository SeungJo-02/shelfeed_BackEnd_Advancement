package com.shelfeed.backend.domain.member.vo;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
// AttributeConverter가 엔티티를 읽을 때마다 새 인스턴스를 만든다. equals가 없으면 Hibernate의
// 더티 체킹이 스냅샷과 동일성(identity)으로 비교해 항상 "변경됨"으로 판정하고, 순수 조회에도
// members 전체 컬럼 UPDATE가 flush마다 나간다. 값 비교로 바꿔 읽기 경로의 쓰기를 없앤다.
@EqualsAndHashCode
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class NotificationPreferences {
    @Builder.Default private boolean likeEnabled            = true;
    @Builder.Default private boolean commentEnabled         = true;
    @Builder.Default private boolean followEnabled          = true;
    @Builder.Default private boolean followingReviewEnabled = true;
}
