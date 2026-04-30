package com.shelfeed.backend.domain.book.service;

import com.shelfeed.backend.domain.book.client.AladinApiClient;
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
import com.shelfeed.backend.global.common.exception.BusinessException;
import com.shelfeed.backend.global.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BookService {

    private final BookRepository bookRepository;
    private final MemberRepository memberRepository;
    private final LibraryRepository libraryRepository;
    private final ReviewRepository reviewRepository;
    private final ReviewLikeRepository reviewLikeRepository;
    private final AladinApiClient aladinApiClient;


    // 1. 도서 검색
    @Transactional
    public BookSearchListResponse searchBooks(BookSearchRequest request, Long memberUserId) {
        AladinSearchResponse response = aladinApiClient.search(request.getQuery(), 1, request.getLimit() + 1); //무한스크롤을 위해 +1 개 더 조회
        if (response == null || response.getItems() == null) {
            return BookSearchListResponse.of(List.of(), request.getLimit());// 내용없으면 빈 리스트
        }

        List<AladinItem> items = response.getItems();

        //ISBN 목록으로 기존 도서 일괄 조회
        List<String> isbns = items.stream().map(AladinItem::getIsbn13).toList();
        Map<String, Book> existingBooks = bookRepository.findByIsbn13In(isbns).stream()
                .collect(Collectors.toMap(Book::getIsbn13, b -> b));

        // DB에 없는 도서만 일괄 저장
        List<Book> newBooks = items.stream()
                .filter(item -> !existingBooks.containsKey(item.getIsbn13()))
                .map(this::createBookFromItem)
                .toList();
        if (!newBooks.isEmpty()) bookRepository.saveAll(newBooks);

        // 전체 도서 목록 구성 (기존 + 신규)
        Map<String, Book> allBooksMap = new HashMap<>(existingBooks);
        newBooks.forEach(b -> allBooksMap.put(b.getIsbn13(), b));
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
        List<Review> reviews = switch (request.getSort()){//필터링
            case "popular" -> reviewRepository.findBookReviewsPopular(bookId, request.getCursor(), PageRequest.of(0,pageSize));
            case "rating_high" -> reviewRepository.findBookReviewsRatingHigh(bookId, PageRequest.of(0, pageSize));
            case "rating_low"  -> reviewRepository.findBookReviewsRatingLow(bookId, PageRequest.of(0, pageSize));
            default -> reviewRepository.findBookReviewsLatest(
                    bookId, request.getCursor(), PageRequest.of(0, pageSize));
        };
        List<Long> reviewIds = reviews.stream().map(Review::getReviewId).toList();
        Set<Long> likedIds = memberUserId != null ? reviewLikeRepository.findLikedReviewIds(reviewIds, memberUserId) : Set.of();
        //set으로 하는게 성능이 더 좋으니깐 사용
        List<BookReviewResponse> content = reviews.stream()
                .map(review -> BookReviewResponse.of(review, likedIds.contains(review.getReviewId())))
                .toList();

        return BookReviewListResponse.of(content, request.getLimit());
    }

    // 알라딘 아이템 → Book 엔티티 생성 (저장 없이 객체만 반환, saveAll용)
    private Book createBookFromItem(AladinItem item) {
        LocalDate pubDate = null;
        try {
            pubDate = LocalDate.parse(item.getPubDate());
        } catch (Exception ignored) {}
        Integer totalPages = item.getSubInfo() != null ? item.getSubInfo().getItemPage() : null;
        return Book.create(
                item.getIsbn13(), item.getTitle(), item.getAuthor(), item.getPublisher(),
                item.getCover(), item.getDescription(), totalPages, pubDate,
                item.getItemId() != null ? String.valueOf(item.getItemId()) : null,
                item.getCategoryName()
        );
    }

    // 알라딘 아이템 → DB Book (없으면 저장) - 단건 조회용 (getBookByIsbn 등)
    private Book findOrCreateBook(AladinItem item) {
        return bookRepository.findByIsbn13(item.getIsbn13())
                .orElseGet(() -> bookRepository.save(createBookFromItem(item)));
    }
    //맵버 찾거나 없으면 null
    private Member getMemberOrNull(Long memberUserId) {
        return memberRepository.findByMemberUserId(memberUserId).orElse(null);
    }
}
