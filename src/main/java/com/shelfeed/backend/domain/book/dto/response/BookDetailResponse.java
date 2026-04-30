package com.shelfeed.backend.domain.book.dto.response;

import com.shelfeed.backend.domain.book.entity.Book;
import com.shelfeed.backend.domain.library.enums.ReadingStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;

@Getter
@Builder
public class BookDetailResponse {
    private Long bookId;
    private String isbn13;
    private String title;
    private String author;
    private String publisher;
    private String coverImageUrl;
    private String description;
    private Integer totalPages;
    private LocalDate publishedDate;
    private String aladinItemId;
    //isbn 조회 전용
    private Boolean inMyLibrary;
    //도서 상세조회 전용
    private Double averageRating;
    private Long reviewCount;
    private String myLibraryStatus;
    private Long myLibraryBookId;
    private Long myReviewId;
    //4.2 ISBN 조회용
    public static BookDetailResponse ofIsbn(Book book, boolean inMyLibrary) {
        return BookDetailResponse.builder()
                .bookId(book.getBookId())
                .isbn13(book.getIsbn13())
                .title(book.getTitle())
                .author(book.getAuthor())
                .publisher(book.getPublisher())
                .coverImageUrl(book.getCoverImageUrl())
                .description(book.getDescription())
                .totalPages(book.getTotalPages())
                .publishedDate(book.getPublishedDate())
                .aladinItemId(book.getAladinItemId())
                .inMyLibrary(inMyLibrary)
                .build();
    }

    //도서 상세 조회용
    public static BookDetailResponse of(Book book, Double averageRating, Long reviewCount, ReadingStatus myLibraryStatus, Long myLibraryBookId, Long myReviewId){
        return BookDetailResponse.builder()
                .bookId(book.getBookId())
                .isbn13(book.getIsbn13())
                .title(book.getTitle())
                .author(book.getAuthor())
                .publisher(book.getPublisher())
                .coverImageUrl(book.getCoverImageUrl())
                .description(book.getDescription())
                .totalPages(book.getTotalPages())
                .publishedDate(book.getPublishedDate())
                .aladinItemId(book.getAladinItemId())
                .averageRating(averageRating)
                .reviewCount(reviewCount)
                .myLibraryStatus(myLibraryStatus != null ? myLibraryStatus.name() : null)
                .myLibraryBookId(myLibraryBookId)
                .myReviewId(myReviewId)
                .build();
    }






}
