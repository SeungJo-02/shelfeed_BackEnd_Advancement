package com.shelfeed.backend.domain.feed.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 통합 피드 한 페이지.
 *
 * <p>커서는 마지막 항목의 (createdAt, reviewId)다. 두 값을 함께 넘겨야 같은 시각에
 * 작성된 감상이 여러 건일 때 페이지 경계에서 누락·중복이 생기지 않는다.
 */
@Getter
@Builder
public class FeedListResponse {

    private List<FeedItemResponse> content;
    private LocalDateTime nextCursorCreatedAt;
    private Long nextCursorId;
    private boolean hasNext;
    private int size;

    /**
     * @param content limit보다 1개 더 조회한 목록 (초과분 존재 여부로 hasNext 판정)
     */
    public static FeedListResponse of(List<FeedItemResponse> content, int limit) {
        boolean hasNext = limit > 0 && content.size() > limit;
        List<FeedItemResponse> result = hasNext ? content.subList(0, limit) : content;

        FeedItemResponse last = result.isEmpty() ? null : result.get(result.size() - 1);
        // 다음 페이지가 없으면 커서를 내리지 않는다 — 클라이언트가 끝을 명확히 알 수 있도록.
        LocalDateTime nextCursorCreatedAt = (hasNext && last != null) ? last.getCreatedAt() : null;
        Long nextCursorId = (hasNext && last != null) ? last.getReviewId() : null;

        return FeedListResponse.builder()
                .content(result)
                .nextCursorCreatedAt(nextCursorCreatedAt)
                .nextCursorId(nextCursorId)
                .hasNext(hasNext)
                .size(result.size())
                .build();
    }
}
