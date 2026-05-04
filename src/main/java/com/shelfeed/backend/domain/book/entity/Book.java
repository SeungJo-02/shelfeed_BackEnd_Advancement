package com.shelfeed.backend.domain.book.entity;

import com.shelfeed.backend.global.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
@Entity
@Table(name = "books")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Book extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column
    private Long bookId;

    @Column(nullable = false, length = 13, unique = true)
    private String isbn13;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(nullable = false, length = 50)
    private String author;

    @Column(length = 200)
    private String publisher;

    @Column(length = 500)
    private String coverImageUrl;

    @Column(columnDefinition = "TEXT")
    private String description;

    private Integer totalPages;

    private LocalDate publishedDate;

    @Column(length = 50)
    private String aladinItemId;

    @Column(length = 100)
    private String category;

    @Column(length = 100)
    private String genre;

    public static Book create(String isbn13, String title, String author, String publisher,
                              String coverImageUrl, String description, Integer totalPages,
                              LocalDate publishedDate, String aladinItemId, String category,
                              String genre) {
        Book book = new Book();
        book.isbn13 = isbn13;
        book.title = title;
        book.author = author;
        book.publisher = publisher;
        book.coverImageUrl = coverImageUrl;
        book.description = description;
        book.totalPages = totalPages;
        book.publishedDate = publishedDate;
        book.aladinItemId = aladinItemId;
        book.category = category;
        book.genre = genre;
        return book;
    }
}
