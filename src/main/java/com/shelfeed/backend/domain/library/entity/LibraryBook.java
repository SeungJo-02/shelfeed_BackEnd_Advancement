package com.shelfeed.backend.domain.library.entity;

import com.shelfeed.backend.domain.book.entity.Book;
import com.shelfeed.backend.domain.library.enums.ReadingStatus;
import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.global.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Entity
@Table(name = "library_books", uniqueConstraints = @UniqueConstraint(columnNames = {"member_id","book_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LibraryBook extends BaseTimeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "library_book_id")
    private Long libraryBookId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "book_id", nullable = false)
    private Book book;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReadingStatus status;

    private LocalDate startedAt;
    private LocalDate finishedAt;

    public static LibraryBook create(Member member, Book book, ReadingStatus status){
        LibraryBook libraryBook = new LibraryBook();
        libraryBook.member = member;
        libraryBook.book = book;
        libraryBook.status = status;
        if (status == ReadingStatus.READING) {
            libraryBook.startedAt = LocalDate.now();
        }
        if (status == ReadingStatus.FINISHED) {
            libraryBook.startedAt = LocalDate.now();
            libraryBook.finishedAt = LocalDate.now();
        }
        return libraryBook;
    }
    // 도서 상태 변경하면 날짜 업로드
    public void updateStatus(ReadingStatus newStatus){
        this.status =newStatus;
        if (this.startedAt == null && newStatus == ReadingStatus.READING){//상태가 시작
            this.startedAt = LocalDate.now();//읽기 시작한 날짜기록
        }
        if (newStatus == ReadingStatus.FINISHED) {//상태가 마침
            this.finishedAt = LocalDate.now();// 다읽은 날짜 기록
        }
    }
}
