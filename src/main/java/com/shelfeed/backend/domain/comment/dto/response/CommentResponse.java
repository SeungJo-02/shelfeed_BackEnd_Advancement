package com.shelfeed.backend.domain.comment.dto.response;

import com.shelfeed.backend.domain.comment.entity.Comment;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class CommentResponse {

    private Long commentId;
    private UserInfo user;          // 소프트 삭제 시 null
    private String content;         // 소프트 삭제 시 "삭제된 댓글입니다"
    private Long parentCommentId;
    private int likeCount;
    private Boolean isLiked;        // CommentLikeRepository 연결 후 적용
    private Boolean isDeleted;
    private Boolean isMine;
    private Boolean isEdited;
    private LocalDateTime createdAt;
    private List<ReplyResponse> replies;

    @Getter
    @Builder
    public static class UserInfo {
        private Long userId;
        private String nickname;
        private String profileImageUrl;
    }

    public static CommentResponse of(Comment comment, boolean isMine,boolean isLiked ,List<ReplyResponse> replies) {
        boolean deleted = comment.isDeleted();

        return CommentResponse.builder()
                .commentId(comment.getCommentId())
                .user(deleted ? null : UserInfo.builder()
                        .userId(comment.getMember().getMemberUserId())
                        .nickname(comment.getMember().getNickname())
                        .profileImageUrl(comment.getMember().getProfileImageUrl())
                        .build())
                .content(deleted ? "삭제된 댓글입니다" : comment.getContent())
                .parentCommentId(comment.getParentComment() != null
                        ? comment.getParentComment().getCommentId() : null)
                .likeCount(comment.getLikeCount())
                .isLiked(isLiked)     //CommentLikeRepository 연결 후 적용
                .isDeleted(deleted)
                .isMine(isMine)
                .isEdited(!deleted && comment.getUpdatedAt().isAfter(comment.getCreatedAt().plusSeconds(1)))
                .createdAt(comment.getCreatedAt())
                .replies(replies)
                .build();
    }
}
