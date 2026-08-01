package com.shelfeed.backend.domain.library.dto.response;

import com.shelfeed.backend.domain.book.entity.Book;
import com.shelfeed.backend.domain.library.entity.LibraryBook;
import com.shelfeed.backend.domain.library.enums.ReadingStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;

@Getter
@Builder
public class LibraryBookAddResponse {
    private Long libraryBookId;
    private Long bookId;
    private ReadingStatus status;
    private LocalDate startedAt;
    private LocalDate finishedAt;

    public static LibraryBookAddResponse of(LibraryBook lb, Book book){
        return LibraryBookAddResponse.builder()
                .libraryBookId(lb.getLibraryBookId())
                .bookId(book.getBookId())
                .status(lb.getStatus())
                .startedAt(lb.getStartedAt())
                .finishedAt(lb.getFinishedAt())
                .build();
    }
}
