package com.shelfeed.backend.domain.comment.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class CommentListResponse {
    private List<CommentResponse> content;
    private Long nextCursor;
    private boolean hasNext;
    private int size;

    /**
     * 조회 결과를 목록 응답으로 만든다.
     *
     * <p>{@code hasNext}를 호출측에서 받는다. 예전엔 여기서 {@code content.size() > limit}으로
     * 직접 판단했는데, 서비스가 limit개로 이미 잘라서 넘겨주기 때문에 그 조건은 항상 거짓이었다.
     * 그래서 댓글이 아무리 많아도 {@code hasNext=false}, {@code nextCursor=null}이 나가
     * 무한 스크롤이 첫 페이지에서 멈췄다. 다음 페이지 존재 여부는 자르기 전 크기를 아는
     * 서비스만 판단할 수 있으므로 인자로 넘겨받는다.
     *
     * @param cursorPaged 커서로 이어 볼 수 있는 정렬이면 true. 인기순은 commentId가 단조롭지
     *                    않아 커서를 쓸 수 없으므로 false를 넘겨 {@code nextCursor}를 비운다.
     */
    public static CommentListResponse of(List<CommentResponse> content, boolean hasNext, boolean cursorPaged){
        Long nextCursor = (hasNext && cursorPaged && !content.isEmpty())
                ? content.get(content.size() - 1).getCommentId()
                : null;

        return CommentListResponse.builder()
                .content(content)
                .nextCursor(nextCursor)
                .hasNext(hasNext)
                .size(content.size())
                .build();
    }
}
