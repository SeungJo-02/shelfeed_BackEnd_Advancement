package com.shelfeed.backend.domain.library.dto.response;

import com.shelfeed.backend.domain.book.entity.Book;
import com.shelfeed.backend.domain.library.entity.LibraryBook;
import com.shelfeed.backend.domain.library.enums.ReadingStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;

@Getter
@Builder
public class LibraryBookSummaryResponse {
    private Long libraryBookId;
    private BookSummary book;
    private ReadingStatus status;
    private LocalDate startedAt;
    private LocalDate finishedAt;
    private boolean hasReview;

    @Getter
    @Builder
    public static class BookSummary{
        private Long bookId;
        private String isbn13;
        private String title;
        private String author;
        private String coverImageUrl;
    }

    public static LibraryBookSummaryResponse of(LibraryBook lb, Book book){
        return LibraryBookSummaryResponse.builder()
                .libraryBookId(lb.getLibraryBookId())
                .book(BookSummary.builder()
                        .bookId(book.getBookId())
                        .isbn13(book.getIsbn13())
                        .title(book.getTitle())
                        .author(book.getAuthor())
                        .coverImageUrl(book.getCoverImageUrl())
                        .build())
                .status(lb.getStatus())
                .startedAt(lb.getStartedAt())
                .finishedAt(lb.getFinishedAt())
                .hasReview(false)   // ReviewRepository 연결 후 적용
                .build();
    }



}
