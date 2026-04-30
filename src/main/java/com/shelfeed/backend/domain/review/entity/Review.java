package com.shelfeed.backend.domain.review.entity;

import com.shelfeed.backend.domain.book.entity.Book;
import com.shelfeed.backend.domain.library.entity.LibraryBook;
import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.review.enums.ReviewStatus;
import com.shelfeed.backend.domain.review.enums.ReviewVisibility;
import com.shelfeed.backend.global.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "reviews")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Review extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "review_id")
    private Long reviewId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "book_id", nullable = false)
    private Book book;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "library_book_id")
    private LibraryBook libraryBook;

    @Column(nullable = false)
    private byte rating;

    @Column(columnDefinition = "Text")
    private String content;

    private Integer readPages;

    @Column(nullable = false)
    private boolean isSpoiler;

    @Column(columnDefinition = "Text")
    private String quote;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ReviewStatus reviewStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ReviewVisibility reviewVisibility;

    @Column(nullable = false)
    private int likeCount = 0;

    @Column(nullable = false)
    private int commentCount = 0;

    @Column(nullable = false)
    private boolean isDeleted = false;

    private LocalDateTime deletedAt;


    //정적 메서드
    //리뷰 적을 때 컬럼 값을 제공해줘야하는 기본 메서드 불완전한 객체 방지
    public static Review create(Member member, Book book, LibraryBook libraryBook,
                                byte rating, String content, String quote ,boolean isSpoiler,
                                Integer readPages ,ReviewVisibility reviewVisibility, ReviewStatus reviewStatus) {
        Review review = new Review();
        review.member = member;
        review.book = book;
        review.libraryBook = libraryBook;
        review.rating = rating;
        review.content = content;
        review.quote = quote;
        review.readPages = readPages;
        review.isSpoiler = isSpoiler;
        review.reviewVisibility = reviewVisibility;
        review.reviewStatus = reviewStatus;
        return review;
    }
    //업댓
    public void update(byte rating, String content, String quote, Integer readPages, boolean isSpoiler,
                       ReviewVisibility reviewVisibility, ReviewStatus reviewStatus){
        this.rating = rating;
        this.content = content;
        this.quote = quote;
        this.readPages = readPages;
        this.isSpoiler = isSpoiler;
        this.reviewVisibility = reviewVisibility;
        this.reviewStatus = reviewStatus;
    }

    // 비즈니스 메서드
    // 공개 범위 변경
    public void changeVisibility(ReviewVisibility visibility){this.reviewVisibility = visibility;}

    //소프트 삭제
    public void softDelete(){
        this.isDeleted = true;
        this.deletedAt = LocalDateTime.now();
    }
}
