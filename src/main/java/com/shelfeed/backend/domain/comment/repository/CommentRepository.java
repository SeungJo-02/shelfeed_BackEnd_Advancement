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
