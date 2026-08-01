package com.shelfeed.backend.domain.library.service;

import com.shelfeed.backend.domain.book.entity.Book;
import com.shelfeed.backend.domain.book.repository.BookRepository;
import com.shelfeed.backend.domain.follow.repository.FollowRepository;
import com.shelfeed.backend.domain.library.dto.request.LibraryBookAddRequest;
import com.shelfeed.backend.domain.library.dto.request.LibraryStatusUpdateRequest;
import com.shelfeed.backend.domain.library.dto.response.*;
import com.shelfeed.backend.domain.library.entity.LibraryBook;
import com.shelfeed.backend.domain.library.enums.ReadingStatus;
import com.shelfeed.backend.domain.library.repository.LibraryRepository;
import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.member.enums.LibraryVisibility;
import com.shelfeed.backend.domain.review.entity.Review;
import com.shelfeed.backend.domain.review.repository.ReviewRepository;
import com.shelfeed.backend.global.common.exception.BusinessException;
import com.shelfeed.backend.global.common.exception.ErrorCode;
import com.shelfeed.backend.global.common.helper.MemberLoader;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;
import java.util.Set;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LibraryService {
    private final MemberLoader memberLoader;
    private final BookRepository bookRepository;
    private final ReviewRepository reviewRepository;
    private final LibraryRepository libraryRepository;
    private final FollowRepository followRepository;

    //1. 서재 도서 추가
    @Transactional
    public LibraryBookAddResponse addBook(Long memberUserId, LibraryBookAddRequest request){
        Member member = memberLoader.getOrThrow(memberUserId);
        Book book = getBook(request.getBookId());
        //이미 서제에 있으면 에러
        if (libraryRepository.existsByMemberIdAndBookId(member.getMemberId(), request.getBookId())){
            throw new BusinessException(ErrorCode.ALREADY_IN_LIBRARY);
        }
        LibraryBook libraryBook = LibraryBook.create(member.getMemberId(), book.getBookId(), request.getStatus());
        // 동시 요청 경쟁: exists 동시 통과 후 unique 위반을 409로 멱등 변환 (saveAndFlush로 즉시 검출)
        try {
            libraryRepository.saveAndFlush(libraryBook);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.ALREADY_IN_LIBRARY);
        }
        return LibraryBookAddResponse.of(libraryBook, book);
    }

    //2. 내 서재 목록 조회
    public LibraryListResponse getMyLibrary(Long memberUserId, ReadingStatus status, Long cursor, int limit){
        Member member = memberLoader.getOrThrow(memberUserId);
        //Id 기반 페이지 네이션
        List<LibraryBook> books = libraryRepository.findLibraryBooks(member.getMemberId(), status, cursor, PageRequest.of(0, limit + 1));
        return LibraryListResponse.of(toSummaries(books), limit);
    }

    //3. 서재 도서 상세조회
    public LibraryBookDetailResponse getLibraryBookDetail(Long libraryBookId, Long memberUserId){
        Member member = memberLoader.getOrThrow(memberUserId);
        LibraryBook libraryBook = libraryRepository.findByLibraryBookIdAndMemberId(libraryBookId, member.getMemberId())
                .orElseThrow(()->new BusinessException(ErrorCode.LIBRARY_BOOK_NOT_FOUND));
        Review review = reviewRepository.findByMemberAndBook_BookIdAndIsDeletedFalse(member, libraryBook.getBookId())
                .orElse(null);
        return LibraryBookDetailResponse.of(libraryBook, getBook(libraryBook.getBookId()), review);
    }

    //4. 독서 상태 변경
    @Transactional
    public LibraryStatusUpdateResponse updateStatus(Long libraryBookId, Long memberUserId, LibraryStatusUpdateRequest request){
        Member member = memberLoader.getOrThrow(memberUserId);
        LibraryBook libraryBook = libraryRepository.findByLibraryBookIdAndMemberId(libraryBookId, member.getMemberId())
                .orElseThrow(()->new BusinessException(ErrorCode.LIBRARY_BOOK_NOT_FOUND));
        libraryBook.updateStatus(request.getStatus());
        return LibraryStatusUpdateResponse.of(libraryBook);
    }

    //5. 서재에서 도서 제거
    @Transactional
    public void removeBook(Long libraryBookId, Long memberUserId){
        Member member = memberLoader.getOrThrow(memberUserId);
        LibraryBook libraryBook = libraryRepository.findByLibraryBookIdAndMemberId(libraryBookId, member.getMemberId())
                .orElseThrow(()->new BusinessException(ErrorCode.LIBRARY_BOOK_NOT_FOUND));
        if (reviewRepository.existsByMember_MemberUserIdAndBook_BookIdAndIsDeletedFalse(memberUserId, libraryBook.getBookId())) {
            throw new BusinessException(ErrorCode.REVIEW_EXISTS);
        }
        libraryRepository.delete(libraryBook);
    }

    //6. 타 유저 서재 목록 조회
    public UserLibraryResponse getUserLibrary(Long userId, ReadingStatus status, Long cursor, int limit, Long requestingUserId) {
        Member member = memberLoader.getOrThrow(userId);
        // 본인 서재는 공개 여부 무관하게 조회, 팔로워도 비공개 무관하게 조회
        boolean isSelf = userId.equals(requestingUserId);
        if (!isSelf && member.getLibraryVisibility() == LibraryVisibility.PRIVATE) {
            boolean isFollower = requestingUserId != null &&
                    followRepository.existsByFollowerAndFollowee(memberLoader.getOrThrow(requestingUserId), member);
            if (!isFollower) {
                return UserLibraryResponse.ofPrivate();
            }
        }

        List<LibraryBook> books = libraryRepository.findLibraryBooks(member.getMemberId(), status, cursor, PageRequest.of(0, limit + 1));
        return UserLibraryResponse.of(toSummaries(books), limit);
    }

    //7. 지혜의 탑 — 내 완독 도서 목록
    public WisdomTowerResponse getWisdomTower(Long memberUserId) {
        Member member = memberLoader.getOrThrow(memberUserId);
        List<LibraryBook> books = libraryRepository.findFinishedBooksForTower(member.getMemberId());
        return WisdomTowerResponse.of(books, loadBooks(books));
    }

    /** 도서는 연관관계가 아니므로 bookId를 모아 IN 쿼리 한 번으로 조회한다(JOIN FETCH 대체). */
    private Map<Long, Book> loadBooks(List<LibraryBook> libraryBooks) {
        Set<Long> bookIds = libraryBooks.stream().map(LibraryBook::getBookId).collect(Collectors.toSet());
        if (bookIds.isEmpty()) return Map.of();
        return bookRepository.findAllById(bookIds).stream()
                .collect(Collectors.toMap(Book::getBookId, b -> b));
    }

    private List<LibraryBookSummaryResponse> toSummaries(List<LibraryBook> books) {
        Map<Long, Book> bookMap = loadBooks(books);
        return books.stream()
                .map(lb -> LibraryBookSummaryResponse.of(lb, bookMap.get(lb.getBookId())))
                .toList();
    }

    private Book getBook(Long bookId){
        return bookRepository.findById(bookId).orElseThrow(() -> new BusinessException(ErrorCode.BOOK_NOT_FOUND));
    }
}
