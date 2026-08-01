package com.shelfeed.backend.domain.review.entity;

import com.shelfeed.backend.domain.library.entity.LibraryBook;
import com.shelfeed.backend.domain.review.enums.ReviewVisibility;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "review_drafts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewDraft {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "drafts_id")
    private Long draftsId;

    // 서비스 경계(review → user)를 넘으므로 ID로 참조한다. members.member_id(PK)를 담는다.
    @Column(name = "member_id", nullable = false)
    private Long memberId;

    // 서비스 경계(review → catalog)를 넘으므로 ID로 참조한다. books.book_id(PK)를 담는다.
    @Column(name = "book_id")
    private Long bookId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "library_book_id")
    private LibraryBook libraryBook;

    private Byte rating;

    @Column(columnDefinition = "TEXT")
    private String content;

    private Integer readPages;

    private Boolean isSpoiler;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private ReviewVisibility reviewVisibility;

    //정적 메서드
    public static ReviewDraft create(Long memberId, Long bookId){
        ReviewDraft draft = new ReviewDraft();
        draft.memberId = memberId;
        draft.bookId = bookId;
        return draft;
    }

    //비즈니스 메서드
    public void update(Byte rating, String content, Integer readPages, Boolean isSpoiler,ReviewVisibility reviewVisibility){
        this.rating = rating;
        this.content = content;
        this.readPages = readPages;
        this.isSpoiler = isSpoiler;
        this.reviewVisibility = reviewVisibility;
    }

}
