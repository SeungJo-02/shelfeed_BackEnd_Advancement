package com.shelfeed.backend.domain.book.document;

import com.shelfeed.backend.domain.book.entity.Book;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.Setting;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Document(indexName = "books", createIndex = false)
@Setting(settingPath = "elasticsearch/books-settings.json")
public class BookDocument {

    @Id
    private Long bookId;

    @Field(type = FieldType.Text, analyzer = "korean_analyzer")
    private String title;

    @Field(type = FieldType.Text, analyzer = "korean_analyzer")
    private String author;

    @Field(type = FieldType.Keyword)
    private String isbn13;

    @Field(type = FieldType.Keyword)
    private String publisher;

    @Field(type = FieldType.Keyword)
    private String coverImageUrl;

    @Builder
    private BookDocument(Long bookId, String title, String author, String isbn13,
                         String publisher, String coverImageUrl) {
        this.bookId = bookId;
        this.title = title;
        this.author = author;
        this.isbn13 = isbn13;
        this.publisher = publisher;
        this.coverImageUrl = coverImageUrl;
    }

    public static BookDocument from(Book book) {
        return BookDocument.builder()
                .bookId(book.getBookId())
                .title(book.getTitle())
                .author(book.getAuthor())
                .isbn13(book.getIsbn13())
                .publisher(book.getPublisher())
                .coverImageUrl(book.getCoverImageUrl())
                .build();
    }
}
