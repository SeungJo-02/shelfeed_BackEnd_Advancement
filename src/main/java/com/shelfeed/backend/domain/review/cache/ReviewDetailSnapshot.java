package com.shelfeed.backend.domain.review.cache;

import com.shelfeed.backend.domain.review.dto.response.ReviewDetailResponse;
import com.shelfeed.backend.domain.review.entity.Review;
import com.shelfeed.backend.domain.review.enums.ReviewStatus;
import com.shelfeed.backend.domain.review.enums.ReviewVisibility;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 감상 상세의 "사람과 무관한" 부분만 떼어낸 스냅샷. 캐시에 담기는 단위다.
 *
 * <p>isMine/isLiked처럼 보는 사람마다 달라지는 값은 넣지 않는다. 그 둘만 빼두면 한 번 담은
 * 스냅샷을 모든 요청이 공유할 수 있다. 대신 그 둘을 DB 없이 계산하려면 재료가 필요해서,
 * 응답에는 안 나가는 {@code ownerMemberUserId}를 같이 들고 있는다(본인 확인·차단 확인용).
 *
 * <p>엔티티가 아니라 이 record를 담는 이유는 두 가지다. 첫째, 엔티티는 지연 로딩 프록시를 달고
 * 있어서 트랜잭션 밖에서 필드를 건드리면 터진다. 둘째, JSON으로 직렬화해 Redis에 넣어야 한다.
 */
public record ReviewDetailSnapshot(
        Long reviewId,
        Long ownerMemberUserId,
        UserInfo user,
        BookInfo book,
        byte rating,
        String content,
        String quote,
        Integer readPages,
        boolean spoiler,
        ReviewVisibility reviewVisibility,
        ReviewStatus reviewStatus,
        int viewCount,
        int likeCount,
        int commentCount,
        List<String> tags,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public record UserInfo(Long userId, String nickname, String profileImageUrl) {
    }

    public record BookInfo(Long bookId, String isbn13, String title, String author, String coverImageUrl) {
    }

    /** 트랜잭션 안에서만 호출할 것 — 여기서 지연 로딩된 member/book을 모두 읽어 값으로 굳힌다. */
    public static ReviewDetailSnapshot from(Review review, List<String> tags) {
        return new ReviewDetailSnapshot(
                review.getReviewId(),
                review.getMember().getMemberUserId(),
                new UserInfo(
                        review.getMember().getMemberUserId(),
                        review.getMember().getNickname(),
                        review.getMember().getProfileImageUrl()),
                new BookInfo(
                        review.getBook().getBookId(),
                        review.getBook().getIsbn13(),
                        review.getBook().getTitle(),
                        review.getBook().getAuthor(),
                        review.getBook().getCoverImageUrl()),
                review.getRating(),
                review.getContent(),
                review.getQuote(),
                review.getReadPages(),
                review.isSpoiler(),
                review.getReviewVisibility(),
                review.getReviewStatus(),
                review.getViewCount(),
                review.getLikeCount(),
                review.getCommentCount(),
                tags == null ? List.of() : List.copyOf(tags),
                review.getCreatedAt(),
                review.getUpdatedAt());
    }

    /** 보는 사람에 따라 달라지는 두 값만 얹어 응답으로 만든다. DB를 건드리지 않는다. */
    public ReviewDetailResponse toResponse(boolean isMine, boolean isLiked) {
        return ReviewDetailResponse.builder()
                .reviewId(reviewId)
                .user(ReviewDetailResponse.UserInfo.builder()
                        .userId(user.userId())
                        .nickname(user.nickname())
                        .profileImageUrl(user.profileImageUrl())
                        .build())
                .book(ReviewDetailResponse.BookInfo.builder()
                        .bookId(book.bookId())
                        .isbn13(book.isbn13())
                        .title(book.title())
                        .author(book.author())
                        .coverImageUrl(book.coverImageUrl())
                        .build())
                .rating(rating)
                .content(content)
                .quote(quote)
                .readPages(readPages)
                .isSpoiler(spoiler)
                .reviewVisibility(reviewVisibility)
                .reviewStatus(reviewStatus)
                .viewCount(viewCount)
                .likeCount(likeCount)
                .commentCount(commentCount)
                .isLiked(isLiked)
                .isMine(isMine)
                .tags(tags)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .build();
    }
}
