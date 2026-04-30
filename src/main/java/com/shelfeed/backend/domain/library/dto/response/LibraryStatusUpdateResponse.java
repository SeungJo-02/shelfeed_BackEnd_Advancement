package com.shelfeed.backend.domain.library.dto.response;

import com.shelfeed.backend.domain.library.entity.LibraryBook;
import com.shelfeed.backend.domain.library.enums.ReadingStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;

@Getter
@Builder
public class LibraryStatusUpdateResponse {

    private Long libraryBookId;
    private ReadingStatus status;
    private LocalDate startedAt;
    private LocalDate finishedAt;

    public static LibraryStatusUpdateResponse of(LibraryBook lb) {
        return LibraryStatusUpdateResponse.builder()
                .libraryBookId(lb.getLibraryBookId())
                .status(lb.getStatus())
                .startedAt(lb.getStartedAt())
                .finishedAt(lb.getFinishedAt())
                .build();
    }
}
