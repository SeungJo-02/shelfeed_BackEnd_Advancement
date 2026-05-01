package com.shelfeed.backend.domain.library;

import com.shelfeed.backend.domain.book.entity.Book;
import com.shelfeed.backend.domain.book.repository.BookRepository;
import com.shelfeed.backend.domain.library.dto.request.LibraryBookAddRequest;
import com.shelfeed.backend.domain.library.dto.request.LibraryStatusUpdateRequest;
import com.shelfeed.backend.domain.library.dto.response.LibraryBookAddResponse;
import com.shelfeed.backend.domain.library.dto.response.LibraryBookDetailResponse;
import com.shelfeed.backend.domain.library.dto.response.LibraryListResponse;
import com.shelfeed.backend.domain.library.dto.response.UserLibraryResponse;
import com.shelfeed.backend.domain.library.entity.LibraryBook;
import com.shelfeed.backend.domain.library.enums.ReadingStatus;
import com.shelfeed.backend.domain.library.repository.LibraryRepository;
import com.shelfeed.backend.domain.library.service.LibraryService;
import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.member.enums.LibraryVisibility;
import com.shelfeed.backend.global.common.helper.MemberLoader;
import com.shelfeed.backend.domain.review.entity.Review;
import com.shelfeed.backend.domain.review.repository.ReviewRepository;
import com.shelfeed.backend.global.common.exception.BusinessException;
import com.shelfeed.backend.global.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("LibraryService 단위 테스트")
class LibraryServiceTest {

    @Mock MemberLoader memberLoader;
    @Mock BookRepository bookRepository;
    @Mock ReviewRepository reviewRepository;
    @Mock LibraryRepository libraryRepository;

    @InjectMocks LibraryService libraryService;

    private Member member;
    private Member otherMember;
    private Member privateMember;
    private Book book;
    private LibraryBook libraryBook;

    @BeforeEach
    void setUp() {
        member = Member.createLocal(1L, "me@test.com", "encoded", "me", "bio");
        otherMember = Member.createLocal(2L, "other@test.com", "encoded", "other", "bio");
        privateMember = Member.createLocal(3L, "priv@test.com", "encoded", "priv", "bio");
        ReflectionTestUtils.setField(privateMember, "libraryVisibility", LibraryVisibility.PRIVATE);

        book = mock(Book.class);
        lenient().when(book.getBookId()).thenReturn(10L);
        lenient().when(book.getIsbn13()).thenReturn("9781234567890");
        lenient().when(book.getTitle()).thenReturn("Test Book");
        lenient().when(book.getAuthor()).thenReturn("Author");
        lenient().when(book.getPublisher()).thenReturn("Publisher");
        lenient().when(book.getCoverImageUrl()).thenReturn("http://cover.url");
        lenient().when(book.getTotalPages()).thenReturn(300);

        libraryBook = LibraryBook.create(member, book, ReadingStatus.WANT_TO_READ);
        ReflectionTestUtils.setField(libraryBook, "libraryBookId", 100L);
    }

    private LibraryBookAddRequest addRequest(Long bookId, ReadingStatus status) {
        LibraryBookAddRequest req = new LibraryBookAddRequest();
        ReflectionTestUtils.setField(req, "bookId", bookId);
        ReflectionTestUtils.setField(req, "status", status);
        return req;
    }

    private LibraryStatusUpdateRequest statusRequest(ReadingStatus status) {
        LibraryStatusUpdateRequest req = new LibraryStatusUpdateRequest();
        ReflectionTestUtils.setField(req, "status", status);
        return req;
    }

    @Nested
    @DisplayName("서재 도서 추가")
    class AddBook {

        @Test
        @DisplayName("성공")
        void 성공() {
            given(memberLoader.getOrThrow(1L)).willReturn(member);
            given(bookRepository.findById(10L)).willReturn(Optional.of(book));
            given(libraryRepository.existsByMemberAndBook_BookId(member, 10L)).willReturn(false);
            given(libraryRepository.save(any(LibraryBook.class))).willAnswer(inv -> {
                LibraryBook lb = inv.getArgument(0);
                ReflectionTestUtils.setField(lb, "libraryBookId", 100L);
                return lb;
            });

            LibraryBookAddResponse response = libraryService.addBook(1L, addRequest(10L, ReadingStatus.WANT_TO_READ));

            assertThat(response).isNotNull();
            assertThat(response.getStatus()).isEqualTo(ReadingStatus.WANT_TO_READ);
            then(libraryRepository).should().save(any(LibraryBook.class));
        }

        @Test
        @DisplayName("MEMBER_NOT_FOUND 예외")
        void 멤버_없음_예외() {
            given(memberLoader.getOrThrow(1L)).willThrow(new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

            assertThatThrownBy(() -> libraryService.addBook(1L, addRequest(10L, ReadingStatus.WANT_TO_READ)))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEMBER_NOT_FOUND);
        }

        @Test
        @DisplayName("BOOK_NOT_FOUND 예외")
        void 책_없음_예외() {
            given(memberLoader.getOrThrow(1L)).willReturn(member);
            given(bookRepository.findById(10L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> libraryService.addBook(1L, addRequest(10L, ReadingStatus.WANT_TO_READ)))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.BOOK_NOT_FOUND);
        }

        @Test
        @DisplayName("ALREADY_IN_LIBRARY 예외")
        void 이미_서재에_있음_예외() {
            given(memberLoader.getOrThrow(1L)).willReturn(member);
            given(bookRepository.findById(10L)).willReturn(Optional.of(book));
            given(libraryRepository.existsByMemberAndBook_BookId(member, 10L)).willReturn(true);

            assertThatThrownBy(() -> libraryService.addBook(1L, addRequest(10L, ReadingStatus.WANT_TO_READ)))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ALREADY_IN_LIBRARY);
        }
    }

    @Nested
    @DisplayName("내 서재 목록 조회")
    class GetMyLibrary {

        @Test
        @DisplayName("성공 - 상태 필터 조회")
        void 성공_상태_필터() {
            given(memberLoader.getOrThrow(1L)).willReturn(member);
            given(libraryRepository.findLibraryBooks(eq(member), eq(ReadingStatus.READING), isNull(), any(PageRequest.class)))
                    .willReturn(List.of(libraryBook));

            LibraryListResponse response = libraryService.getMyLibrary(1L, ReadingStatus.READING, null, 10);

            assertThat(response).isNotNull();
            assertThat(response.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("성공 - 전체 조회 (status=null)")
        void 성공_전체_조회() {
            given(memberLoader.getOrThrow(1L)).willReturn(member);
            given(libraryRepository.findLibraryBooks(eq(member), isNull(), isNull(), any(PageRequest.class)))
                    .willReturn(List.of(libraryBook));

            LibraryListResponse response = libraryService.getMyLibrary(1L, null, null, 10);

            assertThat(response).isNotNull();
            assertThat(response.isHasNext()).isFalse();
        }

        @Test
        @DisplayName("MEMBER_NOT_FOUND 예외")
        void 멤버_없음_예외() {
            given(memberLoader.getOrThrow(1L)).willThrow(new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

            assertThatThrownBy(() -> libraryService.getMyLibrary(1L, null, null, 10))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEMBER_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("서재 도서 상세 조회")
    class GetLibraryBookDetail {

        @Test
        @DisplayName("성공 - 리뷰 있음")
        void 성공_리뷰_있음() {
            Review review = mock(Review.class);
            lenient().when(review.getReviewId()).thenReturn(200L);
            lenient().when(review.getRating()).thenReturn((byte) 4);
            lenient().when(review.getContent()).thenReturn("좋은 책");

            given(memberLoader.getOrThrow(1L)).willReturn(member);
            given(libraryRepository.findByLibraryBookIdAndMember(100L, member)).willReturn(Optional.of(libraryBook));
            given(reviewRepository.findByMemberAndBook_BookIdAndIsDeletedFalse(member, 10L)).willReturn(Optional.of(review));

            LibraryBookDetailResponse response = libraryService.getLibraryBookDetail(100L, 1L);

            assertThat(response).isNotNull();
            assertThat(response.getReview()).isNotNull();
            assertThat(response.getReview().getReviewId()).isEqualTo(200L);
        }

        @Test
        @DisplayName("성공 - 리뷰 없음")
        void 성공_리뷰_없음() {
            given(memberLoader.getOrThrow(1L)).willReturn(member);
            given(libraryRepository.findByLibraryBookIdAndMember(100L, member)).willReturn(Optional.of(libraryBook));
            given(reviewRepository.findByMemberAndBook_BookIdAndIsDeletedFalse(member, 10L)).willReturn(Optional.empty());

            LibraryBookDetailResponse response = libraryService.getLibraryBookDetail(100L, 1L);

            assertThat(response).isNotNull();
            assertThat(response.getReview()).isNull();
        }

        @Test
        @DisplayName("MEMBER_NOT_FOUND 예외")
        void 멤버_없음_예외() {
            given(memberLoader.getOrThrow(1L)).willThrow(new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

            assertThatThrownBy(() -> libraryService.getLibraryBookDetail(100L, 1L))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEMBER_NOT_FOUND);
        }

        @Test
        @DisplayName("LIBRARY_BOOK_NOT_FOUND 예외")
        void 서재_책_없음_예외() {
            given(memberLoader.getOrThrow(1L)).willReturn(member);
            given(libraryRepository.findByLibraryBookIdAndMember(100L, member)).willReturn(Optional.empty());

            assertThatThrownBy(() -> libraryService.getLibraryBookDetail(100L, 1L))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.LIBRARY_BOOK_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("독서 상태 변경")
    class UpdateStatus {

        @Test
        @DisplayName("성공 - 상태가 변경됨")
        void 성공() {
            given(memberLoader.getOrThrow(1L)).willReturn(member);
            given(libraryRepository.findByLibraryBookIdAndMember(100L, member)).willReturn(Optional.of(libraryBook));

            libraryService.updateStatus(100L, 1L, statusRequest(ReadingStatus.READING));

            assertThat(libraryBook.getStatus()).isEqualTo(ReadingStatus.READING);
            assertThat(libraryBook.getStartedAt()).isNotNull();
        }

        @Test
        @DisplayName("MEMBER_NOT_FOUND 예외")
        void 멤버_없음_예외() {
            given(memberLoader.getOrThrow(1L)).willThrow(new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

            assertThatThrownBy(() -> libraryService.updateStatus(100L, 1L, statusRequest(ReadingStatus.READING)))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEMBER_NOT_FOUND);
        }

        @Test
        @DisplayName("LIBRARY_BOOK_NOT_FOUND 예외")
        void 서재_책_없음_예외() {
            given(memberLoader.getOrThrow(1L)).willReturn(member);
            given(libraryRepository.findByLibraryBookIdAndMember(100L, member)).willReturn(Optional.empty());

            assertThatThrownBy(() -> libraryService.updateStatus(100L, 1L, statusRequest(ReadingStatus.READING)))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.LIBRARY_BOOK_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("서재 도서 제거")
    class RemoveBook {

        @Test
        @DisplayName("성공")
        void 성공() {
            given(memberLoader.getOrThrow(1L)).willReturn(member);
            given(libraryRepository.findByLibraryBookIdAndMember(100L, member)).willReturn(Optional.of(libraryBook));
            given(reviewRepository.existsByMember_MemberUserIdAndBook_BookIdAndIsDeletedFalse(1L, 10L)).willReturn(false);

            libraryService.removeBook(100L, 1L);

            then(libraryRepository).should().delete(libraryBook);
        }

        @Test
        @DisplayName("MEMBER_NOT_FOUND 예외")
        void 멤버_없음_예외() {
            given(memberLoader.getOrThrow(1L)).willThrow(new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

            assertThatThrownBy(() -> libraryService.removeBook(100L, 1L))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEMBER_NOT_FOUND);
        }

        @Test
        @DisplayName("LIBRARY_BOOK_NOT_FOUND 예외")
        void 서재_책_없음_예외() {
            given(memberLoader.getOrThrow(1L)).willReturn(member);
            given(libraryRepository.findByLibraryBookIdAndMember(100L, member)).willReturn(Optional.empty());

            assertThatThrownBy(() -> libraryService.removeBook(100L, 1L))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.LIBRARY_BOOK_NOT_FOUND);
        }

        @Test
        @DisplayName("REVIEW_EXISTS 예외 - 리뷰 존재 시 제거 불가")
        void 리뷰_있으면_제거_불가_예외() {
            given(memberLoader.getOrThrow(1L)).willReturn(member);
            given(libraryRepository.findByLibraryBookIdAndMember(100L, member)).willReturn(Optional.of(libraryBook));
            given(reviewRepository.existsByMember_MemberUserIdAndBook_BookIdAndIsDeletedFalse(1L, 10L)).willReturn(true);

            assertThatThrownBy(() -> libraryService.removeBook(100L, 1L))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.REVIEW_EXISTS);
        }
    }

    @Nested
    @DisplayName("타 유저 서재 조회")
    class GetUserLibrary {

        @Test
        @DisplayName("성공 - 본인 PRIVATE 서재는 본인이 조회 시 내용을 반환한다 (visibility는 PUBLIC으로 응답)")
        void 성공_본인_서재() {
            given(memberLoader.getOrThrow(3L)).willReturn(privateMember);
            given(libraryRepository.findLibraryBooks(eq(privateMember), isNull(), isNull(), any(PageRequest.class)))
                    .willReturn(List.of());

            UserLibraryResponse response = libraryService.getUserLibrary(3L, null, null, 10, 3L);

            assertThat(response.getLibraryVisibility()).isEqualTo(LibraryVisibility.PUBLIC);
            assertThat(response.isHasNext()).isFalse();
        }

        @Test
        @DisplayName("성공 - 타인 PUBLIC 서재")
        void 성공_타인_PUBLIC() {
            given(memberLoader.getOrThrow(2L)).willReturn(otherMember);
            given(libraryRepository.findLibraryBooks(eq(otherMember), isNull(), isNull(), any(PageRequest.class)))
                    .willReturn(List.of(libraryBook));

            UserLibraryResponse response = libraryService.getUserLibrary(2L, null, null, 10, 1L);

            assertThat(response.getLibraryVisibility()).isEqualTo(LibraryVisibility.PUBLIC);
            assertThat(response.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("성공 - 타인 PRIVATE 서재 → 빈 응답 반환")
        void 성공_타인_PRIVATE() {
            given(memberLoader.getOrThrow(3L)).willReturn(privateMember);

            UserLibraryResponse response = libraryService.getUserLibrary(3L, null, null, 10, 1L);

            assertThat(response.getLibraryVisibility()).isEqualTo(LibraryVisibility.PRIVATE);
            assertThat(response.getContent()).isEmpty();
            assertThat(response.isHasNext()).isFalse();
        }

        @Test
        @DisplayName("MEMBER_NOT_FOUND 예외")
        void 멤버_없음_예외() {
            given(memberLoader.getOrThrow(2L)).willThrow(new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

            assertThatThrownBy(() -> libraryService.getUserLibrary(2L, null, null, 10, 1L))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEMBER_NOT_FOUND);
        }
    }
}
