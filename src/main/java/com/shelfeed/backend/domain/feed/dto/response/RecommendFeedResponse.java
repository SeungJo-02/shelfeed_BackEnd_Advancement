package com.shelfeed.backend.domain.feed.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class RecommendFeedResponse {

    private List<RecommendItemResponse> content;
    private Long nextCursorId;
    private Integer nextCursorLike;
    private boolean hasNext;
    private int size;
    private String recommendType; // CONTENT_BASED | SOCIAL | MIXED | POPULAR

    public static RecommendFeedResponse of(List<RecommendItemResponse> content, int limit, String recommendType) {
        boolean hasNext = limit > 0 && content.size() > limit;
        List<RecommendItemResponse> result = hasNext ? content.subList(0, limit) : content;
        Long nextCursorId = (hasNext && !result.isEmpty()) ? result.get(result.size() - 1).getReviewId() : null;
        Integer nextCursorLike = (hasNext && !result.isEmpty()) ? result.get(result.size() - 1).getLikeCount() : null;

        return RecommendFeedResponse.builder()
                .content(result)
                .nextCursorId(nextCursorId)
                .nextCursorLike(nextCursorLike)
                .hasNext(hasNext)
                .size(result.size())
                .recommendType(recommendType)
                .build();
    }
}
