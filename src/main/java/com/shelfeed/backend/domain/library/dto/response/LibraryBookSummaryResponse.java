package com.shelfeed.backend.domain.library.dto.response;

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

    public static LibraryBookSummaryResponse of(LibraryBook lb){
        return LibraryBookSummaryResponse.builder()
                .libraryBookId(lb.getLibraryBookId())
                .book(BookSummary.builder()
                        .bookId(lb.getBook().getBookId())
                        .isbn13(lb.getBook().getIsbn13())
                        .title(lb.getBook().getTitle())
                        .author(lb.getBook().getAuthor())
                        .coverImageUrl(lb.getBook().getCoverImageUrl())
                        .build())
                .status(lb.getStatus())
                .startedAt(lb.getStartedAt())
                .finishedAt(lb.getFinishedAt())
                .hasReview(false)   // ReviewRepository 연결 후 적용
                .build();
    }



}
