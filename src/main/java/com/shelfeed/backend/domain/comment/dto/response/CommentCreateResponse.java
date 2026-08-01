package com.shelfeed.backend.domain.comment.dto.response;

import com.shelfeed.backend.domain.comment.entity.Comment;
import com.shelfeed.backend.domain.member.entity.Member;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class CommentCreateResponse {

    private Long commentId;
    private Long reviewId;
    private UserInfo user;
    private String content;
    private Long parentCommentId;
    private int likeCount;
    private LocalDateTime createdAt;

    @Getter
    @Builder
    public static class UserInfo{
        private Long userId;
        private String nickname;
        private String profileImageUrl;
    }

    public static CommentCreateResponse of(Comment comment, Member author){
        return CommentCreateResponse.builder()
                .commentId(comment.getCommentId())
                .reviewId(comment.getReview().getReviewId())
                .user(UserInfo.builder()
                        .userId(author.getMemberUserId())
                        .nickname(author.getNickname())
                        .profileImageUrl(author.getProfileImageUrl())
                        .build())
                .content(comment.getContent())
                .parentCommentId(comment.getParentComment() != null ? comment.getParentComment().getCommentId(): null )
                .likeCount(comment.getLikeCount())
                .createdAt(comment.getCreatedAt())
                .build();

    }

}
