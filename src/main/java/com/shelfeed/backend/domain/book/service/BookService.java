package com.shelfeed.backend.domain.book.service;

import com.shelfeed.backend.domain.book.client.AladinClient;
import com.shelfeed.backend.domain.book.client.dto.AladinItem;
import com.shelfeed.backend.domain.book.client.dto.AladinSearchResponse;
import com.shelfeed.backend.domain.book.dto.request.BookReviewSearchRequest;
import com.shelfeed.backend.domain.book.dto.request.BookSearchRequest;
import com.shelfeed.backend.domain.book.dto.response.*;
import com.shelfeed.backend.domain.book.entity.Book;
import com.shelfeed.backend.domain.book.repository.BookRepository;
import com.shelfeed.backend.domain.library.entity.LibraryBook;
import com.shelfeed.backend.domain.library.enums.ReadingStatus;
import com.shelfeed.backend.domain.library.repository.LibraryRepository;
import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.member.repository.MemberRepository;
import com.shelfeed.backend.domain.review.entity.Review;
import com.shelfeed.backend.domain.review.repository.ReviewLikeRepository;
import com.shelfeed.backend.domain.review.repository.ReviewRepository;
import com.shelfeed.backend.domain.block.service.BlockService;
import com.shelfeed.backend.global.common.exception.BusinessException;
import com.shelfeed.backend.global.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

    private final BookRepository bookRepository;
    private final MemberRepository memberRepository;
    private final LibraryRepository libraryRepository;
    private final ReviewRepository reviewRepository;
    private final ReviewLikeRepository reviewLikeRepository;
    private final AladinClient aladinApiClient;
    private final BlockService blockService;
    private final BookPersistenceService bookPersistenceService;


    // 1. 도서 검색 — 외부 HTTP는 트랜잭션 밖(NOT_SUPPORTED), DB 쓰기는 BookPersistenceService 위임
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
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

        Map<String, Book> allBooksMap = bookPersistenceService.upsertAndGetBooks(items).books();
        List<Book> allBooks = items.stream()
                .map(item -> allBooksMap.get(item.getIsbn13()))
                .filter(Objects::nonNull)
                .toList();

        Member member = memberUserId != null ? getMemberOrNull(memberUserId) : null;

        // 서재 여부 IN절 일괄 조회
        Set<Long> myLibraryBookIds = Set.of();
        if (member != null && !allBooks.isEmpty()) {
            List<Long> bookIds = allBooks.stream().map(Book::getBookId).toList();
            myLibraryBookIds = libraryRepository.findBookIdsByMemberAndBookIdIn(member.getMemberId(), bookIds);
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
                Optional<LibraryBook> libraryBook = libraryRepository.findByMemberIdAndBookId(member.getMemberId(), bookId);
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

    // 3. ISBN 조회 — 외부 HTTP는 트랜잭션 밖(NOT_SUPPORTED), DB 쓰기는 BookPersistenceService 위임
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public BookDetailResponse getBookByIsbn(String isbn13, Long memberUserId) {
        Optional<Book> existing = bookRepository.findByIsbn13(isbn13);
        Book book = existing.orElseGet(() -> {
            AladinSearchResponse response = aladinApiClient.lookupByIsbn(isbn13);
            if (response == null || response.getItems() == null || response.getItems().isEmpty()) {
                throw new BusinessException(ErrorCode.BOOK_NOT_FOUND);
            }
            return bookPersistenceService.findOrCreateBook(response.getItems().get(0));
        });

        boolean inMyLibrary = false;
        if (memberUserId != null) {
            Member member = getMemberOrNull(memberUserId);
            if (member != null) {
                inMyLibrary = libraryRepository.existsByMemberIdAndBookId(member.getMemberId(), book.getBookId());
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
        // 좋아요 조회에도 PK가 필요하므로 me를 블록 밖에서 한 번만 로드해 재사용한다.
        Member me = memberUserId != null ? getMemberOrNull(memberUserId) : null;
        if (me != null && !reviews.isEmpty()) {
            {
                Set<Long> blocked = blockService.blockedIdSet(me);
                if (!blocked.isEmpty()) {
                    reviews = reviews.stream()
                            .filter(r -> !blocked.contains(r.getMember().getMemberUserId()))
                            .collect(Collectors.toList());
                }
            }
        }
        List<Long> reviewIds = reviews.stream().map(Review::getReviewId).toList();
        Set<Long> likedIds = me != null ? reviewLikeRepository.findLikedReviewIds(reviewIds, me.getMemberId()) : Set.of();
        //set으로 하는게 성능이 더 좋으니깐 사용
        List<BookReviewResponse> content = reviews.stream()
                .map(review -> BookReviewResponse.of(review, likedIds.contains(review.getReviewId())))
                .toList();

        return BookReviewListResponse.of(content, request.getLimit(), isRatingSort, isPopularSort);
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

    /**
     * SearchService가 통합 검색 전 호출하는 알라딘 캐싱 메서드.
     * DB에 없는 도서를 INSERT 후 ES 색인까지 완료한다.
     * 외부 HTTP는 트랜잭션 밖(NOT_SUPPORTED)에서 수행하고, DB 쓰기는 BookPersistenceService에 위임한다.
     * @return ES 색인까지 모두 성공한 경우만 true — 색인 실패 시 false 반환하여 호출자가 Redis 마커를 찍지 않도록 함
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean syncFromAladin(String query, int maxResults) {
        AladinSearchResponse response = aladinApiClient.search(query, 1, maxResults);
        if (response == null || response.getItems() == null || response.getItems().isEmpty()) return false;
        List<AladinItem> items = deduplicateByIsbn(response.getItems());
        if (items.isEmpty()) return false;
        return bookPersistenceService.upsertAndGetBooks(items).indexed();
    }
}
