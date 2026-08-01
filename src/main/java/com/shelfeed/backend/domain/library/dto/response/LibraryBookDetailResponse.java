package com.shelfeed.backend.domain.library.dto.response;

import com.shelfeed.backend.domain.book.entity.Book;
import com.shelfeed.backend.domain.library.entity.LibraryBook;
import com.shelfeed.backend.domain.library.enums.ReadingStatus;
import com.shelfeed.backend.domain.review.entity.Review;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Builder
public class LibraryBookDetailResponse {

    private Long libraryBookId;
    private BookDetail book;
    private ReadingStatus status;
    private LocalDate startedAt;
    private LocalDate finishedAt;
    private ReviewSummary review;


    @Getter
    @Builder
    public static class BookDetail{
        private Long bookId;
        private String isbn13;
        private String title;
        private String author;
        private String publisher;
        private String coverImageUrl;
        private Integer totalPages;
    }

    @Getter
    @Builder
    public static class ReviewSummary{
        private Long reviewId;
        private byte rating;
        private String content;
        private LocalDateTime createdAt;
    }

    public static LibraryBookDetailResponse of(LibraryBook lb, Book book, Review review) {
        return LibraryBookDetailResponse.builder()
                .libraryBookId(lb.getLibraryBookId())
                .book(BookDetail.builder()
                        .bookId(book.getBookId())
                        .isbn13(book.getIsbn13())
                        .title(book.getTitle())
                        .author(book.getAuthor())
                        .publisher(book.getPublisher())
                        .coverImageUrl(book.getCoverImageUrl())
                        .totalPages(book.getTotalPages())
                        .build())
                .status(lb.getStatus())
                .startedAt(lb.getStartedAt())
                .finishedAt(lb.getFinishedAt())
                .review(review == null ? null : ReviewSummary.builder()
                        .reviewId(review.getReviewId())
                        .rating(review.getRating())
                        .content(review.getContent())
                        .createdAt(review.getCreatedAt())
                        .build())
                .build();
    }
}
