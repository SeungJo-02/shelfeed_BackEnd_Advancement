package com.shelfeed.backend.domain.library.dto.response;

import com.shelfeed.backend.domain.library.entity.LibraryBook;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

@Getter
@Builder
public class WisdomTowerResponse {

    private int totalCount;
    private List<TowerItem> books;

    @Getter
    @Builder
    public static class TowerItem {
        private Long libraryBookId;
        private Long bookId;
        private String title;
        private LocalDate finishedAt;

        public static TowerItem of(LibraryBook lb) {
            return TowerItem.builder()
                    .libraryBookId(lb.getLibraryBookId())
                    .bookId(lb.getBook().getBookId())
                    .title(lb.getBook().getTitle())
                    .finishedAt(lb.getFinishedAt())
                    .build();
        }
    }

    public static WisdomTowerResponse of(List<LibraryBook> books) {
        List<TowerItem> items = books.stream().map(TowerItem::of).toList();
        return WisdomTowerResponse.builder()
                .totalCount(items.size())
                .books(items)
                .build();
    }
}
