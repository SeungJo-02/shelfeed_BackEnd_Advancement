package com.shelfeed.backend.domain.book.service;

import com.shelfeed.backend.domain.book.client.AladinClient;
import com.shelfeed.backend.domain.book.client.dto.AladinItem;
import com.shelfeed.backend.domain.book.client.dto.AladinSearchResponse;
import com.shelfeed.backend.domain.book.document.BookDocument;
import com.shelfeed.backend.domain.book.dto.request.BookReviewSearchRequest;
import com.shelfeed.backend.domain.book.dto.request.BookSearchRequest;
import com.shelfeed.backend.domain.book.dto.response.*;
import com.shelfeed.backend.domain.book.entity.Book;
import com.shelfeed.backend.domain.book.repository.BookRepository;
import com.shelfeed.backend.domain.book.repository.BookSearchRepository;
import com.shelfeed.backend.domain.library.entity.LibraryBook;
import com.shelfeed.backend.domain.library.enums.ReadingStatus;
import com.shelfeed.backend.domain.library.repository.LibraryRepository;
import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.member.repository.MemberRepository;
import com.shelfeed.backend.domain.review.entity.Review;
import com.shelfeed.backend.domain.review.repository.ReviewLikeRepository;
import com.shelfeed.backend.domain.review.repository.ReviewRepository;
import com.shelfeed.backend.domain.block.repository.BlockRepository;
import com.shelfeed.backend.global.common.exception.BusinessException;
import com.shelfeed.backend.global.common.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BookService {

    @PersistenceContext
    private EntityManager entityManager;

    private final BookRepository bookRepository;
    private final BookSearchRepository bookSearchRepository;
    private final ElasticsearchOperations elasticsearchOperations;
    private final MemberRepository memberRepository;
    private final LibraryRepository libraryRepository;
    private final ReviewRepository reviewRepository;
    private final ReviewLikeRepository reviewLikeRepository;
    private final AladinClient aladinApiClient;
    private final BlockRepository blockRepository;


    // 1. 도서 검색
    @Transactional
    public BookSearchListResponse searchBooks(BookSearchRequest request, Long memberUserId) {
        AladinSearchResponse response = aladinApiClient.search(request.getQuery(), request.getPage(), request.getLimit() + 1); //무한스크롤을 위해 +1 개 더 조회
        if (response == null || response.getItems() == null) {
            return BookSearchListResponse.of(List.of(), request.getLimit());// 내용없으면 빈 리스트
        }

        // isbn13 null/blank 제거 + 응답 내 중복 제거 후 DB upsert (순서 유지)
        List<AladinItem> items = deduplicateByIsbn(response.getItems());
        if (items.isEmpty()) {
            return BookSearchListResponse.of(List.of(), request.getLimit());
        }

        Map<String, Book> allBooksMap = upsertAndGetBooks(items).books();
        List<Book> allBooks = items.stream()
                .map(item -> allBooksMap.get(item.getIsbn13()))
                .filter(Objects::nonNull)
                .toList();

        Member member = memberUserId != null ? getMemberOrNull(memberUserId) : null;

        // 서재 여부 IN절 일괄 조회
        Set<Long> myLibraryBookIds = Set.of();
        if (member != null && !allBooks.isEmpty()) {
            List<Long> bookIds = allBooks.stream().map(Book::getBookId).toList();
            myLibraryBookIds = libraryRepository.findBookIdsByMemberAndBookIdIn(member, bookIds);
        }

        final Set<Long> finalMyLibraryBookIds = myLibraryBookIds;
        List<BookSummaryResponse> content = allBooks.stream()
                .map(book -> BookSummaryResponse.of(book, finalMyLibraryBookIds.contains(book.getBookId())))
                .toList();

        return BookSearchListResponse.of(content, request.getLimit());
    }


    // 2. 도서 상세 조회
    public BookDetailResponse getBook(Long bookId, Long memberUserId) {
        //책 없으면 예외
        Book book = bookRepository.findById(bookId).orElseThrow(()->new BusinessException(ErrorCode.BOOK_NOT_FOUND));
        Double averageRating = bookRepository.findAverageRatingByBookId(bookId);//평균 별점
        Long reviewCount = bookRepository.countReviewsByBookId(bookId);//리뷰 카운트
        ReadingStatus myLibraryStatus = null;
        Long myLibraryBookId = null;
        Long myReviewId = null;

        if (memberUserId != null) {
            Member member = getMemberOrNull(memberUserId);
            if (member != null) {
                Optional<LibraryBook> libraryBook = libraryRepository.findByMemberAndBook_BookId(member, bookId);
                if (libraryBook.isPresent()) {
                    myLibraryStatus = libraryBook.get().getStatus();
                    myLibraryBookId = libraryBook.get().getLibraryBookId();
                }

                Optional<Review> myReview = reviewRepository.findByMemberAndBook_BookIdAndIsDeletedFalse(member, bookId);
                if (myReview.isPresent()) {
                    myReviewId = myReview.get().getReviewId();
                }
            }
        }
        return BookDetailResponse.of(book,averageRating,reviewCount,myLibraryStatus,myLibraryBookId,myReviewId);
    }

    // 3. ISBN 조회
    @Transactional
    public BookDetailResponse getBookByIsbn(String isbn13, Long memberUserId) {
        Optional<Book> existing = bookRepository.findByIsbn13(isbn13);
        Book book = existing.orElseGet(() -> {
            AladinSearchResponse response = aladinApiClient.lookupByIsbn(isbn13);
            if (response == null || response.getItems() == null || response.getItems().isEmpty()) {
                throw new BusinessException(ErrorCode.BOOK_NOT_FOUND);
            }
            return findOrCreateBook(response.getItems().get(0));
        });

        boolean inMyLibrary = false;
        if (memberUserId != null) {
            Member member = getMemberOrNull(memberUserId);
            if (member != null) {
                inMyLibrary = libraryRepository.existsByMemberAndBook_BookId(member, book.getBookId());
            }
        }
        return BookDetailResponse.ofIsbn(book, inMyLibrary);
    }

    // 4. 도서별 감상 목록
    public BookReviewListResponse getBookReviews(Long bookId, BookReviewSearchRequest request, Long memberUserId){
        if (!bookRepository.existsById(bookId)){
            throw new BusinessException(ErrorCode.BOOK_NOT_FOUND);
        }
        int pageSize = request.getLimit() + 1;
        boolean isRatingSort = "rating_high".equals(request.getSort()) || "rating_low".equals(request.getSort());
        boolean isPopularSort = "popular".equals(request.getSort());
        List<Review> reviews = switch (request.getSort()){//필터링
            case "popular"     -> reviewRepository.findBookReviewsPopular(bookId, request.getCursorLike(), request.getCursor(), PageRequest.of(0, pageSize));
            case "rating_high" -> reviewRepository.findBookReviewsRatingHigh(bookId, request.getCursorRating(), request.getCursor(), PageRequest.of(0, pageSize));
            case "rating_low"  -> reviewRepository.findBookReviewsRatingLow(bookId, request.getCursorRating(), request.getCursor(), PageRequest.of(0, pageSize));
            default            -> reviewRepository.findBookReviewsLatest(bookId, request.getCursor(), PageRequest.of(0, pageSize));
        };
        if (memberUserId != null && !reviews.isEmpty()) {
            Member me = getMemberOrNull(memberUserId);
            if (me != null) {
                Set<Long> blocked = new HashSet<>(blockRepository.findBlockedIds(me));
                blocked.addAll(blockRepository.findBlockingIds(me));
                if (!blocked.isEmpty()) {
                    reviews = reviews.stream()
                            .filter(r -> !blocked.contains(r.getMember().getMemberUserId()))
                            .collect(Collectors.toList());
                }
            }
        }
        List<Long> reviewIds = reviews.stream().map(Review::getReviewId).toList();
        Set<Long> likedIds = memberUserId != null ? reviewLikeRepository.findLikedReviewIds(reviewIds, memberUserId) : Set.of();
        //set으로 하는게 성능이 더 좋으니깐 사용
        List<BookReviewResponse> content = reviews.stream()
                .map(review -> BookReviewResponse.of(review, likedIds.contains(review.getReviewId())))
                .toList();

        return BookReviewListResponse.of(content, request.getLimit(), isRatingSort, isPopularSort);
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

    // 알라딘 아이템 → DB Book (없으면 저장) - 단건 조회용 (getBookByIsbn 등)
    // 신규 저장 시 ES에도 색인 (실패해도 무시 — BookIndexInitializer로 보강)
    private Book findOrCreateBook(AladinItem item) {
        return bookRepository.findByIsbn13(item.getIsbn13())
                .orElseGet(() -> {
                    Book saved = bookRepository.save(createBookFromItem(item));
                    indexToElasticsearch(List.of(saved));
                    return saved;
                });
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
    //맵버 찾거나 없으면 null
    private Member getMemberOrNull(Long memberUserId) {
        return memberRepository.findByMemberUserId(memberUserId).orElse(null);
    }

    /**
     * 알라딘 응답 아이템에서 isbn13 null/blank 제거 + 중복 제거 (삽입 순서 유지).
     * 알라딘이 동일 ISBN을 카테고리별로 중복 반환하는 경우를 방어한다.
     */
    private List<AladinItem> deduplicateByIsbn(List<AladinItem> rawItems) {
        return rawItems.stream()
                .filter(item -> item.getIsbn13() != null && !item.getIsbn13().isBlank())
                .collect(Collectors.toMap(AladinItem::getIsbn13, i -> i, (a, b) -> a, LinkedHashMap::new))
                .values().stream().toList();
    }

    // ES 색인 성공 여부와 isbn→Book 맵을 함께 반환 — 호출자가 색인 실패 시 Redis 마커 등을 스킵할 수 있도록 분리
    private record UpsertResult(Map<String, Book> books, boolean indexed) {}

    /**
     * DB에 없는 도서만 저장하고 전체 isbn→Book 맵과 ES 색인 결과를 반환한다.
     * searchBooks()와 syncFromAladin() 모두 이 메서드를 통해 upsert 한다.
     */
    private UpsertResult upsertAndGetBooks(List<AladinItem> items) {
        List<String> isbns = items.stream().map(AladinItem::getIsbn13).toList();
        Map<String, Book> existing = bookRepository.findByIsbn13In(isbns).stream()
                .collect(Collectors.toMap(Book::getIsbn13, b -> b));

        List<Book> newBooks = items.stream()
                .filter(item -> !existing.containsKey(item.getIsbn13()))
                .map(this::createBookFromItem)
                .toList();

        Map<String, Book> all = new HashMap<>(existing);
        List<Book> toIndex = new ArrayList<>();
        if (!newBooks.isEmpty()) {
            try {
                // saveAllAndFlush로 즉시 flush — try 블록 내에서 unique 위반 발생하도록
                bookRepository.saveAllAndFlush(newBooks);
                newBooks.forEach(b -> all.put(b.getIsbn13(), b));
                toIndex.addAll(newBooks);
            } catch (DataIntegrityViolationException e) {
                // 동시 요청으로 인한 unique 위반 — Session에 null ID 엔티티가 남아있으므로
                // clear() 후 재조회해야 AssertionFailure를 방지할 수 있다.
                log.warn("도서 saveAll unique 위반 (동시 요청 추정), 재조회 진행: isbns={}", isbns);
                entityManager.clear();
                List<Book> refetched = bookRepository.findByIsbn13In(isbns);
                refetched.forEach(b -> all.put(b.getIsbn13(), b));
                toIndex.addAll(refetched); // 멱등 — 이미 색인된 책도 동일 ID로 덮어쓰기
            }
        }
        // ES 색인은 트랜잭션 외부 효과 — DB 저장 성공 후 best-effort 색인 (성공 여부 반환)
        boolean indexed = indexToElasticsearch(toIndex);
        return new UpsertResult(all, indexed);
    }

    /**
     * SearchService가 통합 검색 전 호출하는 알라딘 캐싱 메서드.
     * DB에 없는 도서를 INSERT 후 ES 색인까지 완료한다.
     * @return ES 색인까지 모두 성공한 경우만 true — 색인 실패 시 false 반환하여 호출자가 Redis 마커를 찍지 않도록 함
     */
    @Transactional
    public boolean syncFromAladin(String query, int maxResults) {
        AladinSearchResponse response = aladinApiClient.search(query, 1, maxResults);
        if (response == null || response.getItems() == null || response.getItems().isEmpty()) return false;
        List<AladinItem> items = deduplicateByIsbn(response.getItems());
        if (items.isEmpty()) return false;
        return upsertAndGetBooks(items).indexed();
    }
}
