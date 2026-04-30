package com.shelfeed.backend.domain.book.dto.response;

import com.shelfeed.backend.domain.book.entity.Book;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;

@Getter
@Builder
public class BookSummaryResponse {

    private Long bookId;
    private String isbn13;
    private String title;
    private String author;
    private String publisher;
    private String coverImageUrl;
    private LocalDate publishedDate;
    private Boolean inMyLibrary;

    public static BookSummaryResponse of(Book book, boolean inMyLibrary){
        return BookSummaryResponse.builder()
                .bookId(book.getBookId())
                .isbn13(book.getIsbn13())
                .title(book.getTitle())
                .author(book.getAuthor())
                .publisher(book.getPublisher())
                .coverImageUrl(book.getCoverImageUrl())
                .publishedDate(book.getPublishedDate())
                .inMyLibrary(inMyLibrary)
                .build();
    }
}
