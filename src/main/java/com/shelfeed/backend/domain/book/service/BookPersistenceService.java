package com.shelfeed.backend.domain.book.service;

import com.shelfeed.backend.domain.book.client.dto.AladinItem;
import com.shelfeed.backend.domain.book.document.BookDocument;
import com.shelfeed.backend.domain.book.entity.Book;
import com.shelfeed.backend.domain.book.repository.BookRepository;
import com.shelfeed.backend.domain.book.repository.BookSearchRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 알라딘 도서 영속화 전담 빈.
 * 외부 HTTP 호출은 BookService가 트랜잭션 밖에서 수행하고,
 * DB 쓰기/ES 색인만 이 빈의 트랜잭션 가진 메서드로 위임받는다.
 * (별도 빈 → 프록시 경유로 새 트랜잭션이 정상적으로 시작됨)
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BookPersistenceService {

    @PersistenceContext
    private EntityManager entityManager;

    private final BookRepository bookRepository;
    private final BookSearchRepository bookSearchRepository;
    private final ElasticsearchOperations elasticsearchOperations;

    // ES 색인 성공 여부와 isbn→Book 맵을 함께 반환 — 호출자가 색인 실패 시 Redis 마커 등을 스킵할 수 있도록 분리
    public record UpsertResult(Map<String, Book> books, boolean indexed) {}

    /**
     * DB에 없는 도서만 저장하고 전체 isbn→Book 맵과 ES 색인 결과를 반환한다.
     * searchBooks()와 syncFromAladin() 모두 이 메서드를 통해 upsert 한다.
     */
    @Transactional
    public UpsertResult upsertAndGetBooks(List<AladinItem> items) {
        List<String> isbns = items.stream().map(AladinItem::getIsbn13).toList();
        Map<String, Book> existing = bookRepository.findByIsbn13In(isbns).stream()
                .collect(Collectors.toMap(Book::getIsbn13, b -> b));

        List<Book> newBooks = items.stream()
                .filter(item -> !existing.containsKey(item.getIsbn13()))
                .map(this::createBookFromItem)
                .toList();

        Map<String, Book> all = new HashMap<>(existing);
        if (!newBooks.isEmpty()) {
            try {
                // saveAllAndFlush로 즉시 flush — try 블록 내에서 unique 위반 발생하도록
                bookRepository.saveAllAndFlush(newBooks);
                newBooks.forEach(b -> all.put(b.getIsbn13(), b));
            } catch (DataIntegrityViolationException e) {
                // 동시 요청으로 인한 unique 위반 — Session에 null ID 엔티티가 남아있으므로
                // clear() 후 재조회해야 AssertionFailure를 방지할 수 있다.
                log.warn("도서 saveAll unique 위반 (동시 요청 추정), 재조회 진행: isbns={}", isbns);
                entityManager.clear();
                List<Book> refetched = bookRepository.findByIsbn13In(isbns);
                refetched.forEach(b -> all.put(b.getIsbn13(), b));
            }
        }
        // [Fix A] 신규/기존 가리지 않고 이번 검색의 알라딘 결과 전체를 멱등 재색인한다.
        // 신규 도서만 색인하면 "DB엔 있는데 ES엔 없는" 책(시드 데이터·과거 색인 실패분 등)이
        // 통합검색에서 영영 안 잡힌다. 전체 재색인(동일 ID 덮어쓰기)으로 DB↔ES 드리프트를 자가치유한다.
        // 재색인량은 검색 limit 크기로 바운드되어 부담이 작다.
        List<Book> toIndex = new ArrayList<>(all.values());
        boolean indexed = indexToElasticsearch(toIndex);
        return new UpsertResult(all, indexed);
    }

    // 알라딘 아이템 → DB Book (없으면 저장) - 단건 조회용 (getBookByIsbn 등)
    // 신규 저장 시 ES에도 색인 (실패해도 무시 — BookIndexInitializer로 보강)
    @Transactional
    public Book findOrCreateBook(AladinItem item) {
        return bookRepository.findByIsbn13(item.getIsbn13())
                .orElseGet(() -> {
                    Book saved = bookRepository.save(createBookFromItem(item));
                    indexToElasticsearch(List.of(saved));
                    return saved;
                });
    }

    // 알라딘 아이템 → Book 엔티티 생성 (저장 없이 객체만 반환, saveAll용)
    private Book createBookFromItem(AladinItem item) {
        LocalDate pubDate = null;
        try {
            pubDate = LocalDate.parse(item.getPubDate());
        } catch (Exception ignored) {}
        Integer totalPages = item.getSubInfo() != null ? item.getSubInfo().getItemPage() : null;
        String author = item.getAuthor();
        if (author != null && author.length() > 50) author = author.substring(0, 50);
        return Book.create(
                item.getIsbn13(), item.getTitle(), author, item.getPublisher(),
                item.getCover(), item.getDescription(), totalPages, pubDate,
                item.getItemId() != null ? String.valueOf(item.getItemId()) : null,
                item.getCategoryName(),
                extractGenre(item.getCategoryName())
        );
    }

    // "국내도서>소설/시/희곡>한국소설" → "소설/시/희곡", "소설" → "소설" (단일 계층도 보존)
    private String extractGenre(String categoryName) {
        if (categoryName == null || categoryName.isBlank()) return null;
        String[] parts = categoryName.split(">");
        return parts.length >= 2 ? parts[1].trim() : parts[0].trim();
    }

    // ES 색인 — 실패해도 트랜잭션 영향 없음 (BookIndexInitializer로 재동기화 가능)
    // refresh() 호출로 색인 즉시 검색 가능 상태로 전환 — 같은 요청에서 알라딘 캐싱 → 검색 사용 가능
    private boolean indexToElasticsearch(List<Book> books) {
        if (books.isEmpty()) return true;
        try {
            List<BookDocument> docs = books.stream().map(BookDocument::from).toList();
            bookSearchRepository.saveAll(docs);
            elasticsearchOperations.indexOps(BookDocument.class).refresh();
            return true;
        } catch (Exception e) {
            log.warn("ES 색인 실패 (count={}), 검색에 즉시 반영 안 됨: error={}",
                    books.size(), e.getMessage());
            return false;
        }
    }
}
