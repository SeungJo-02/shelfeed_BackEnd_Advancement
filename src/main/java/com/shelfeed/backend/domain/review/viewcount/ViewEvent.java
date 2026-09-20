package com.shelfeed.backend.domain.review.viewcount;

/**
 * 조회 이벤트. Kafka로 넘어가는 최소 단위다.
 *
 * @param eventId 중복 전달을 걸러내기 위한 식별자. Kafka는 유실을 막는 대신 중복을 허용하는
 *                at-least-once가 기본이라, 같은 메시지가 두 번 올 수 있다. 담는 것만으로는
 *                멱등이 되지 않고 <b>소비하는 쪽이 이 값을 기억하고 걸러야</b> 성립한다.
 * @param viewedAt 이벤트 발생 시각(epoch millis). 나중에 시간대별 집계를 붙일 때 쓴다.
 */
public record ViewEvent(String eventId, Long reviewId, String viewer, long viewedAt) {}
