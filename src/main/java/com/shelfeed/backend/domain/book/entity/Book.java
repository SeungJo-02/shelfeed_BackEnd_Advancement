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

    /**
     * 외부 카탈로그 상품 번호 (YES24 itemId, 과거 알라딘 itemId). 공급자 교체 시에도 컬럼은 유지한다.
     * 예전 덤프(aladin_item_id)로 복원한 DB는 README의 "DB 마이그레이션" 절대로 컬럼명을 바꿔야 한다 — ddl-auto=update는 rename을 못 한다.
     */
    @Column(length = 50)
    private String externalItemId;

    @Column(length = 100)
    private String category;

    @Column(length = 100)
    private String genre;

    public static Book create(String isbn13, String title, String author, String publisher,
                              String coverImageUrl, String description, Integer totalPages,
                              LocalDate publishedDate, String externalItemId, String category,
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
        book.externalItemId = externalItemId;
        book.category = category;
        book.genre = genre;
        return book;
    }
}
