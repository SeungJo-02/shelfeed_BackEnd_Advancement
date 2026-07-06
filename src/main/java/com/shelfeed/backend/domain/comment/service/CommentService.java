package com.shelfeed.backend.domain.comment.service;

import com.shelfeed.backend.domain.block.service.BlockService;
import com.shelfeed.backend.domain.comment.dto.request.CommentCreateRequest;
import com.shelfeed.backend.domain.notification.service.NotificationService;
import com.shelfeed.backend.domain.comment.dto.request.CommentUpdateRequest;
import com.shelfeed.backend.domain.comment.dto.response.*;
import com.shelfeed.backend.domain.comment.entity.Comment;
import com.shelfeed.backend.domain.comment.entity.CommentLike;
import com.shelfeed.backend.domain.comment.repository.CommentLikeRepository;
import com.shelfeed.backend.domain.comment.repository.CommentRepository;
import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.review.entity.Review;
import com.shelfeed.backend.domain.review.enums.ReviewStatus;
import com.shelfeed.backend.domain.review.enums.ReviewVisibility;
import com.shelfeed.backend.domain.review.repository.ReviewRepository;
import com.shelfeed.backend.global.common.exception.BusinessException;
import com.shelfeed.backend.global.common.exception.ErrorCode;
import com.shelfeed.backend.global.common.helper.MemberLoader;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;



@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CommentService {
    private final MemberLoader memberLoader;
    private final ReviewRepository reviewRepository;
    private final CommentRepository commentRepository;
    private final CommentLikeRepository commentLikeRepository;
    private final NotificationService notificationService;
    private final BlockService blockService;

    //1. 댓글 작성
    @Transactional
    public CommentCreateResponse createComment(Long revireId, Long memberUserId, CommentCreateRequest request){
        Member member = memberLoader.getOrThrow(memberUserId);
        Review review = getReview(revireId);

        if (review.getReviewStatus() != ReviewStatus.PUBLISHED) {
            throw new BusinessException(ErrorCode.REVIEW_NOT_PUBLISHED);
        }

        Member reviewOwner = review.getMember();
        // 비공개 감상은 작성자 본인만 댓글 가능
        if (review.getReviewVisibility() == ReviewVisibility.PRIVATE &&
            !reviewOwner.getMemberUserId().equals(memberUserId)) {
            throw new BusinessException(ErrorCode.PRIVATE_REVIEW);
        }
        // 차단 관계 확인 (양방향)
        if (!reviewOwner.getMemberUserId().equals(memberUserId) &&
            blockService.isBlockedBetween(reviewOwner, member)) {
            throw new BusinessException(ErrorCode.BLOCKED_USER);
        }
        Comment comment;
        Member notifyTarget;
        if (request.getParentCommentId() == null) {//새 댓글이면 새 댓글 생성
            comment = Comment.createOriginComment(review, member, request.getContent());
            notifyTarget = reviewOwner;
        } else {// 댓글이면 부모 댓글에 생성 그것도 아니면 예외
            Comment parentComment = commentRepository.findByCommentIdAndIsDeletedFalse(request.getParentCommentId())
                    .orElseThrow(()-> new BusinessException(ErrorCode.PARENT_COMMENT_NOT_FOUND));
            if (parentComment.getParentComment() != null){ //부모 댓글이 이미 답글 이면 예외
                throw new BusinessException(ErrorCode.NESTED_REPLY_NOT_ALLOWED);
            }
            comment = Comment.createReply(review, member, parentComment, request.getContent());// 대댓글 작성
            notifyTarget = memberLoader.getOrThrow(parentComment.getMember().getMemberUserId());
        }
        commentRepository.save(comment);
        reviewRepository.increaseCommentCount(review.getReviewId());
        notificationService.notifyComment(notifyTarget, member, review.getReviewId(), comment.getCommentId());
        return CommentCreateResponse.of(comment);
    }

    //2. 댓글 조회
    public CommentListResponse getComments(Long reviewId, Long cursor, int limit, Long memberUserId){
        Review review = getReview(reviewId);

        if (review.getReviewVisibility() == ReviewVisibility.PRIVATE) {
            boolean isOwner = memberUserId != null
                    && review.getMember().getMemberUserId().equals(memberUserId);
            if (!isOwner) throw new BusinessException(ErrorCode.PRIVATE_REVIEW);
        }

        List<Comment> parentComments = commentRepository.findParentComments(review, cursor, PageRequest.of(0, limit + 1));
        boolean hasNext = parentComments.size() > limit;
        if (hasNext) parentComments = parentComments.subList(0, limit);

        // 차단 유저 댓글 필터링
        Set<Long> blockedIds = Set.of();
        if (memberUserId != null) {
            Member me = memberLoader.getOrThrow(memberUserId);
            blockedIds = blockService.blockedIdSet(me);
        }
        final Set<Long> finalBlockedIds = blockedIds;
        if (!finalBlockedIds.isEmpty()) {
            parentComments = parentComments.stream()
                    .filter(c -> !finalBlockedIds.contains(c.getMember().getMemberUserId()))
                    .collect(Collectors.toList());
        }

        // 대댓글 IN절 일괄 조회
        List<Comment> allReplies = parentComments.isEmpty()
                ? List.of()
                : commentRepository.findRepliesByParents(parentComments);
        if (!finalBlockedIds.isEmpty() && !allReplies.isEmpty()) {
            allReplies = allReplies.stream()
                    .filter(r -> !finalBlockedIds.contains(r.getMember().getMemberUserId()))
                    .collect(Collectors.toList());
        }
        Map<Long, List<Comment>> repliesMap = allReplies.stream()
                .collect(Collectors.groupingBy(r -> r.getParentComment().getCommentId()));

        // 좋아요 IN절 일괄 조회 (부모 댓글 + 대댓글 한번에)
        Set<Long> likedIds = Set.of();
        if (memberUserId != null) {
            List<Long> allCommentIds = new ArrayList<>();
            parentComments.forEach(c -> allCommentIds.add(c.getCommentId()));
            allReplies.forEach(r -> allCommentIds.add(r.getCommentId()));
            if (!allCommentIds.isEmpty()) {
                likedIds = commentLikeRepository.findLikedCommentIds(allCommentIds, memberUserId);
            }
        }

        final Set<Long> finalLikedIds = likedIds;
        List<CommentResponse> content = parentComments.stream().map(comment -> {
            boolean isMine = memberUserId != null && comment.getMember().getMemberUserId().equals(memberUserId);
            boolean isLiked = finalLikedIds.contains(comment.getCommentId());

            List<ReplyResponse> replies = repliesMap.getOrDefault(comment.getCommentId(), List.of()).stream()
                    .map(reply -> {
                        boolean replyIsMine = memberUserId != null && reply.getMember().getMemberUserId().equals(memberUserId);
                        boolean replyIsLiked = finalLikedIds.contains(reply.getCommentId());
                        return ReplyResponse.of(reply, replyIsMine, replyIsLiked);
                    }).toList();
            return CommentResponse.of(comment, isMine, isLiked, replies);
        }).toList();
        return CommentListResponse.of(content, limit);
    }

    // 3. 댓글 수정
    @Transactional
    public CommentUpdateResponse updateComment(Long reviewId, Long commentId, Long memberUserId, CommentUpdateRequest request){
        Comment comment = getComment(commentId);
        validateCommentOwnerAndReview(comment, reviewId, memberUserId);
        comment.update(request.getContent());
        return CommentUpdateResponse.of(comment);
    }

    // 4. 댓글 삭제
    @Transactional
    public void deleteComment(Long reviewId, Long commentId, Long memberUserId){
        Comment comment = getComment(commentId);
        validateCommentOwnerAndReview(comment, reviewId, memberUserId);
        comment.softDelete();
        reviewRepository.decreaseCommentCount(comment.getReview().getReviewId());
    }
    // 5. 댓글 좋아요
    @Transactional
    public CommentLikeResponse likeComment(Long reviewId, Long commentId, Long memberUserId) {
        Member member = memberLoader.getOrThrow(memberUserId);
        Comment comment = getComment(commentId);
        if (!comment.getReview().getReviewId().equals(reviewId)) {
            throw new BusinessException(ErrorCode.COMMENT_NOT_FOUND);
        }
        Member commentOwner = comment.getMember();
        if (commentOwner.getMemberUserId().equals(memberUserId)) {
            throw new BusinessException(ErrorCode.SELF_LIKE_NOT_ALLOWED);
        }
        // 차단 관계 확인 (양방향) — 감상 좋아요와 동일하게 차단 시 좋아요 자체를 막는다
        if (blockService.isBlockedBetween(commentOwner, member)) {
            throw new BusinessException(ErrorCode.BLOCKED_USER);
        }
        if (commentLikeRepository.existsByComment_CommentIdAndMember_MemberUserId(commentId, memberUserId)){
            throw new BusinessException(ErrorCode.ALREADY_COMMENT_LIKED);
        }
        // 동시 요청 경쟁: exists 동시 통과 후 unique 위반을 409로 멱등 변환 (saveAndFlush로 즉시 검출)
        try {
            commentLikeRepository.saveAndFlush(CommentLike.create(member, comment));
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.ALREADY_COMMENT_LIKED);
        }
        commentRepository.increaseLikeCount(comment.getCommentId());
        notificationService.notifyCommentLike(commentOwner, member, reviewId, comment.getCommentId());
        return CommentLikeResponse.of(getComment(commentId));
    }

    // 6. 댓글 좋아요 취소
    @Transactional
    public CommentLikeResponse unlikeComment(Long reviewId, Long commentId, Long memberUserId) {
        Comment comment = getComment(commentId);
        if (!comment.getReview().getReviewId().equals(reviewId)) {
            throw new BusinessException(ErrorCode.COMMENT_NOT_FOUND);
        }
        // 벌크 DELETE의 실제 삭제 행 수로 감소를 게이팅 — 동시 중복 취소 시 이중 감소(드리프트) 방지
        int deleted = commentLikeRepository.deleteByCommentAndMember(commentId, memberUserId);
        if (deleted == 0) {
            throw new BusinessException(ErrorCode.COMMENT_LIKE_NOT_FOUND);
        }
        commentRepository.decreaseLikeCount(comment.getCommentId());
        return CommentLikeResponse.of(getComment(commentId));
    }

    private void validateCommentOwnerAndReview(Comment comment, Long reviewId, Long memberUserId) {
        if (!comment.getReview().getReviewId().equals(reviewId)) {
            throw new BusinessException(ErrorCode.COMMENT_NOT_FOUND);
        }
        if (!comment.getMember().getMemberUserId().equals(memberUserId)) {
            throw new BusinessException(ErrorCode.NOT_COMMENT_OWNER);
        }
    }

    private Review getReview(Long reviewId) {
        return reviewRepository.findByReviewIdAndIsDeletedFalse(reviewId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REVIEW_NOT_FOUND));
    }

    private Comment getComment(Long commentId) {
        return commentRepository.findByCommentIdAndIsDeletedFalse(commentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMENT_NOT_FOUND));
    }
}
