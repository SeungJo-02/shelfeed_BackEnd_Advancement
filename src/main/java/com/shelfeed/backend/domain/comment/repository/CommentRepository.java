package com.shelfeed.backend.domain.comment.repository;

import com.shelfeed.backend.domain.comment.entity.Comment;
import com.shelfeed.backend.domain.review.entity.Review;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface CommentRepository extends JpaRepository<Comment,Long> {
    //특벙 감상의 원 댓글만 골라서 조회(페이지네이션 방식으로)
    @Query("""
            SELECT c FROM Comment c
            WHERE c.review = :review
            AND c.parentComment IS NULL
            AND (:cursor IS NULL OR c.commentId < :cursor)
            ORDER BY c.commentId DESC
            """) // 훨씬 간단하게 JPQL 작성하는 방법
    List<Comment> findParentComments(@Param("review") Review review, @Param("cursor") Long cursor,
                                     Pageable pageable);

    /**
     * 원 댓글을 인기순(좋아요 → 대댓글 수 → 최신)으로 조회한다. 감상 상세에 미리 펼쳐 보일 몇 개를 고르는 용도다.
     *
     * <p>대댓글 수를 {@code GROUP BY} 대신 ORDER BY 안의 상관 서브쿼리로 센다. 엔티티를 통째로
     * 셀렉트하면서 GROUP BY를 쓰면 MySQL의 {@code ONLY_FULL_GROUP_BY}에 걸리기 때문이다.
     * 서브쿼리 범위가 감상 하나의 댓글로 한정돼 비용도 문제되지 않는다.
     *
     * <p>{@link #findParentComments}와 달리 삭제된 댓글을 뺀다. 최신순 목록은 대댓글이 달린
     * 삭제 댓글을 남겨 대화 흐름을 보존해야 하지만, 인기순은 "읽을 만한 댓글"을 고르는 자리라
     * "삭제된 댓글입니다"가 좋아요 수만으로 맨 위에 오면 미리보기가 망가진다.
     */
    @Query("""
            SELECT c FROM Comment c
            WHERE c.review = :review
            AND c.parentComment IS NULL
            AND c.isDeleted = false
            ORDER BY c.likeCount DESC,
                     (SELECT COUNT(r) FROM Comment r WHERE r.parentComment = c AND r.isDeleted = false) DESC,
                     c.commentId DESC
            """)
    List<Comment> findTopParentComments(@Param("review") Review review, Pageable pageable);

    // 대댓글 조회
    List<Comment> findByParentComment(Comment parentComment);

    // 대댓글 IN절 일괄 조회 (N+1 방지)
    // 작성자는 연관관계가 아니므로 호출측이 memberId를 모아 IN 쿼리 한 번으로 조회해 조립한다.
    @Query("SELECT c FROM Comment c WHERE c.parentComment IN :parents ORDER BY c.commentId ASC")
    List<Comment> findRepliesByParents(@Param("parents") List<Comment> parents);

    // 삭제 안된 감상 조회
    Optional<Comment> findByCommentIdAndIsDeletedFalse(Long commentId);

    // 감상 삭제 시 댓들 모두 소프트 델리트 용도로 사용
    List<Comment> findByReview (Review review);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE Comment c SET c.likeCount = c.likeCount + 1 WHERE c.commentId = :id")
    void increaseLikeCount(@Param("id") Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE Comment c SET c.likeCount = c.likeCount - 1 WHERE c.commentId = :id AND c.likeCount > 0")
    void decreaseLikeCount(@Param("id") Long id);
}
