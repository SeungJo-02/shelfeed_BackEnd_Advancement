package com.shelfeed.backend.domain.library.dto.response;

import com.shelfeed.backend.domain.book.entity.Book;
import com.shelfeed.backend.domain.library.entity.LibraryBook;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

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

        public static TowerItem of(LibraryBook lb, Book book) {
            return TowerItem.builder()
                    .libraryBookId(lb.getLibraryBookId())
                    .bookId(book.getBookId())
                    .title(book.getTitle())
                    .finishedAt(lb.getFinishedAt())
                    .build();
        }
    }

    /** 도서는 연관관계가 아니므로 호출측이 bookId → Book 맵을 조회해 넘긴다. */
    public static WisdomTowerResponse of(List<LibraryBook> books, Map<Long, Book> bookMap) {
        List<TowerItem> items = books.stream()
                .map(lb -> TowerItem.of(lb, bookMap.get(lb.getBookId())))
                .toList();
        return WisdomTowerResponse.builder()
                .totalCount(items.size())
                .books(items)
                .build();
    }
}
