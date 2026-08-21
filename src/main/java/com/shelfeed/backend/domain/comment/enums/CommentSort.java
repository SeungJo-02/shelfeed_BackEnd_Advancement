package com.shelfeed.backend.domain.comment.enums;

/**
 * 댓글 목록의 정렬 기준.
 *
 * <p>두 기준이 페이지네이션 방식까지 가른다. {@link #LATEST}는 commentId 역순이라
 * commentId를 커서로 그대로 쓸 수 있지만, {@link #TOP}은 좋아요·대댓글 수가 순서를 정해
 * commentId가 단조롭지 않다. 그래서 TOP은 커서를 받지 않고 앞에서부터 정해진 개수만 돌려준다.
 */
public enum CommentSort {

    /** 최신순(commentId 역순). 커서 페이지네이션으로 전체를 훑을 때 쓴다. */
    LATEST,

    /** 인기순(좋아요 → 대댓글 수 → 최신). 감상 상세에 미리 펼쳐 보이는 몇 개를 고를 때 쓴다. */
    TOP;

    /**
     * 쿼리 파라미터 문자열을 정렬 기준으로 바꾼다.
     *
     * <p>모르는 값이 와도 예외 대신 {@link #LATEST}로 떨어뜨린다. 정렬 기준은 목록을
     * 어떻게 보여줄지에 대한 선택이지 요청의 유효성 문제가 아니라, 400을 돌려주는 것보다
     * 기본 정렬로 응답하는 편이 낫다.
     */
    public static CommentSort from(String value) {
        if (value == null || value.isBlank()) {
            return LATEST;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return LATEST;
        }
    }
}
