package com.shelfeed.backend.domain.comment.repository;

import com.shelfeed.backend.domain.comment.entity.CommentLike;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface CommentLikeRepository extends JpaRepository<CommentLike,Long> {

    boolean existsByComment_CommentIdAndMemberId(Long commentId, Long memberId);//중복 조회

    Optional<CommentLike> findByComment_CommentIdAndMemberId(Long commentId, Long memberId); //삭제 대상 조회 용도

    // 댓글 좋아요 취소: 벌크 DELETE로 실제 삭제 행 수 반환 → 동시 중복 요청 시 이중 감소(드리프트) 방지
    @Modifying
    @Query("DELETE FROM CommentLike cl WHERE cl.comment.commentId = :commentId AND cl.memberId = :memberId")
    int deleteByCommentAndMember(@Param("commentId") Long commentId, @Param("memberId") Long memberId);

    // 좋아요 IN절 일괄 조회 (N+1 방지)
    @Query("SELECT cl.comment.commentId FROM CommentLike cl WHERE cl.comment.commentId IN :commentIds AND cl.memberId = :memberId")
    Set<Long> findLikedCommentIds(@Param("commentIds") List<Long> commentIds, @Param("memberId") Long memberId);

}
