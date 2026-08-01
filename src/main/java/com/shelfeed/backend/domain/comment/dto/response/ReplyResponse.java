package com.shelfeed.backend.domain.comment.dto.response;

import com.shelfeed.backend.domain.comment.entity.Comment;
import com.shelfeed.backend.domain.member.entity.Member;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class ReplyResponse {
    private Long commentId;
    private UserInfo user;          // 소프트 삭제 시 null
    private String content;         // 소프트 삭제 시 "삭제된 댓글입니다"
    private Long parentCommentId;
    private int likeCount;
    private Boolean isLiked;        //CommentLikeRepository 연결 후 적용
    private Boolean isDeleted;
    private Boolean isMine;
    private Boolean isEdited;
    private LocalDateTime createdAt;

    @Getter
    @Builder
    public static class UserInfo {
        private Long userId;
        private String nickname;
        private String profileImageUrl;
    }

    public static ReplyResponse of(Comment comment, Member author, boolean isMine, boolean isLiked) {
        boolean deleted = comment.isDeleted();

        return ReplyResponse.builder()
                .commentId(comment.getCommentId())
                .user(deleted ? null : UserInfo.builder()
                        .userId(author.getMemberUserId())
                        .nickname(author.getNickname())
                        .profileImageUrl(author.getProfileImageUrl())
                        .build())
                .content(deleted ? "삭제된 댓글입니다" : comment.getContent())
                .parentCommentId(comment.getParentComment().getCommentId())
                .likeCount(comment.getLikeCount())
                .isLiked(isLiked)
                .isDeleted(deleted)
                .isMine(isMine)
                .isEdited(!deleted && comment.getUpdatedAt().isAfter(comment.getCreatedAt().plusSeconds(1)))
                .createdAt(comment.getCreatedAt())
                .build();
    }
}
