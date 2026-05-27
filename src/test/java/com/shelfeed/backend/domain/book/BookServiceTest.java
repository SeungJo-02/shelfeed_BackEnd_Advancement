package com.shelfeed.backend.domain.book;

import com.shelfeed.backend.domain.book.client.AladinClient;
import com.shelfeed.backend.domain.book.client.dto.AladinItem;
import com.shelfeed.backend.domain.book.client.dto.AladinSearchResponse;
import com.shelfeed.backend.domain.book.dto.request.BookReviewSearchRequest;
import com.shelfeed.backend.domain.book.dto.request.BookSearchRequest;
import com.shelfeed.backend.domain.book.dto.response.BookDetailResponse;
import com.shelfeed.backend.domain.book.dto.response.BookReviewListResponse;
import com.shelfeed.backend.domain.book.dto.response.BookSearchListResponse;
import com.shelfeed.backend.domain.book.entity.Book;
import com.shelfeed.backend.domain.book.repository.BookRepository;
import com.shelfeed.backend.domain.book.service.BookService;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("BookService 단위 테스트")
class BookServiceTest {

    @Mock BookRepository bookRepository;
    @Mock MemberRepository memberRepository;
    @Mock LibraryRepository libraryRepository;
    @Mock ReviewRepository reviewRepository;
    @Mock ReviewLikeRepository reviewLikeRepository;
    @Mock AladinClient aladinApiClient;

    @InjectMocks BookService bookService;

    Member member;
    Book book;
    Review review;

    @BeforeEach
    void setUp() {
        member = Member.createLocal(1L, "me@test.com", "encoded", "me", "bio");

        book = Book.create("9781234567890", "Test Book", "Author", "Publisher",
                "http://cover.url", "description", 300, null, "12345", "소설", null);
        ReflectionTestUtils.setField(book, "bookId", 10L);

        review = mock(Review.class);
        lenient().when(review.getReviewId()).thenReturn(50L);
        lenient().when(review.getMember()).thenReturn(member);
        lenient().when(review.getRating()).thenReturn((byte) 4);
        lenient().when(review.getContent()).thenReturn("좋은 책");
        lenient().when(review.getQuote()).thenReturn(null);
        lenient().when(review.isSpoiler()).thenReturn(false);
        lenient().when(review.getLikeCount()).thenReturn(0);
        lenient().when(review.getCommentCount()).thenReturn(0);
        lenient().when(review.getCreatedAt()).thenReturn(null);
    }

    private AladinItem buildAladinItem(String isbn) {
        AladinItem item = new AladinItem();
        item.setIsbn13(isbn);
        item.setTitle("Test Book");
        item.setAuthor("Author");
        item.setPublisher("Publisher");
        item.setCover("http://cover.url");
        item.setDescription("description");
        item.setPubDate("2023-01-01");
        item.setItemId(12345L);
        item.setCategoryName("소설");
        return item;
    }

    private AladinSearchResponse buildAladinResponse(AladinItem item) {
        AladinSearchResponse response = new AladinSearchResponse();
        response.setItems(List.of(item));
        return response;
    }

    @Nested
    @DisplayName("도서 검색 (Aladin API)")
    class SearchBooks {

        @Test
        @DisplayName("성공 - 이미 DB에 있는 책, 로그인 상태")
        void 성공_DB에_있는_책_로그인() {
            AladinItem item = buildAladinItem("9781234567890");
            AladinSearchResponse aladinResponse = buildAladinResponse(item);

            BookSearchRequest request = new BookSearchRequest();
            request.setQuery("test");
            request.setLimit(10);

            given(aladinApiClient.search("test", 1, 11)).willReturn(aladinResponse);
            given(bookRepository.findByIsbn13In(List.of("9781234567890"))).willReturn(List.of(book));
            given(memberRepository.findByMemberUserId(1L)).willReturn(Optional.of(member));
            given(libraryRepository.findBookIdsByMemberAndBookIdIn(eq(member), anyList())).willReturn(Set.of(10L));

            BookSearchListResponse response = bookService.searchBooks(request, 1L);

            assertThat(response).isNotNull();
            assertThat(response.getContent()).hasSize(1);
            assertThat(response.getContent().get(0).getInMyLibrary()).isTrue();
            then(bookRepository).should(never()).saveAll(any());
        }

        @Test
        @DisplayName("성공 - Aladin 응답 null → 빈 목록 반환")
        void 성공_Aladin_null_빈_목록() {
            BookSearchRequest request = new BookSearchRequest();
            request.setQuery("noresult");
            request.setLimit(10);

            given(aladinApiClient.search("noresult", 1, 11)).willReturn(null);

            BookSearchListResponse response = bookService.searchBooks(request, null);

            assertThat(response.getContent()).isEmpty();
        }

        @Test
        @DisplayName("성공 - 비회원 (서재 여부 조회 생략)")
        void 성공_비회원() {
            AladinItem item = buildAladinItem("9781234567890");
            AladinSearchResponse aladinResponse = buildAladinResponse(item);

            BookSearchRequest request = new BookSearchRequest();
            request.setQuery("test");
            request.setLimit(10);

            given(aladinApiClient.search("test", 1, 11)).willReturn(aladinResponse);
            given(bookRepository.findByIsbn13In(List.of("9781234567890"))).willReturn(List.of(book));

            BookSearchListResponse response = bookService.searchBooks(request, null);

            assertThat(response.getContent()).hasSize(1);
            then(libraryRepository).should(never()).findBookIdsByMemberAndBookIdIn(any(), any());
        }
    }

    @Nested
    @DisplayName("도서 상세 조회")
    class GetBook {

        @Test
        @DisplayName("성공 - 비회원")
        void 성공_비회원() {
            given(bookRepository.findById(10L)).willReturn(Optional.of(book));
            given(bookRepository.findAverageRatingByBookId(10L)).willReturn(4.2);
            given(bookRepository.countReviewsByBookId(10L)).willReturn(15L);

            BookDetailResponse response = bookService.getBook(10L, null);

            assertThat(response).isNotNull();
            assertThat(response.getBookId()).isEqualTo(10L);
            assertThat(response.getAverageRating()).isEqualTo(4.2);
            assertThat(response.getMyLibraryStatus()).isNull();
        }

        @Test
        @DisplayName("성공 - 로그인, 서재 있음 + 리뷰 있음")
        void 성공_로그인_서재_리뷰_있음() {
            LibraryBook lb = mock(LibraryBook.class);
            lenient().when(lb.getStatus()).thenReturn(ReadingStatus.READING);
            lenient().when(lb.getLibraryBookId()).thenReturn(100L);

            given(bookRepository.findById(10L)).willReturn(Optional.of(book));
            given(bookRepository.findAverageRatingByBookId(10L)).willReturn(4.0);
            given(bookRepository.countReviewsByBookId(10L)).willReturn(10L);
            given(memberRepository.findByMemberUserId(1L)).willReturn(Optional.of(member));
            given(libraryRepository.findByMemberAndBook_BookId(member, 10L)).willReturn(Optional.of(lb));
            given(reviewRepository.findByMemberAndBook_BookIdAndIsDeletedFalse(member, 10L)).willReturn(Optional.of(review));

            BookDetailResponse response = bookService.getBook(10L, 1L);

            assertThat(response.getMyLibraryStatus()).isEqualTo("READING");
            assertThat(response.getMyLibraryBookId()).isEqualTo(100L);
            assertThat(response.getMyReviewId()).isEqualTo(50L);
        }

        @Test
        @DisplayName("성공 - 로그인, 서재 없음 + 리뷰 없음")
        void 성공_로그인_서재_리뷰_없음() {
            given(bookRepository.findById(10L)).willReturn(Optional.of(book));
            given(bookRepository.findAverageRatingByBookId(10L)).willReturn(null);
            given(bookRepository.countReviewsByBookId(10L)).willReturn(0L);
            given(memberRepository.findByMemberUserId(1L)).willReturn(Optional.of(member));
            given(libraryRepository.findByMemberAndBook_BookId(member, 10L)).willReturn(Optional.empty());
            given(reviewRepository.findByMemberAndBook_BookIdAndIsDeletedFalse(member, 10L)).willReturn(Optional.empty());

            BookDetailResponse response = bookService.getBook(10L, 1L);

            assertThat(response.getMyLibraryStatus()).isNull();
            assertThat(response.getMyReviewId()).isNull();
        }

        @Test
        @DisplayName("BOOK_NOT_FOUND 예외")
        void 책_없음_예외() {
            given(bookRepository.findById(10L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> bookService.getBook(10L, null))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.BOOK_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("ISBN으로 도서 조회")
    class GetBookByIsbn {

        @Test
        @DisplayName("성공 - DB에 이미 존재하는 책")
        void 성공_DB에_있음() {
            given(bookRepository.findByIsbn13("9781234567890")).willReturn(Optional.of(book));

            BookDetailResponse response = bookService.getBookByIsbn("9781234567890", null);

            assertThat(response).isNotNull();
            assertThat(response.getIsbn13()).isEqualTo("9781234567890");
            then(aladinApiClient).should(never()).lookupByIsbn(any());
        }

        @Test
        @DisplayName("성공 - DB에 없어서 Aladin에서 조회 후 저장")
        void 성공_Aladin에서_조회() {
            AladinItem item = buildAladinItem("9781234567890");
            AladinSearchResponse aladinResponse = buildAladinResponse(item);

            given(bookRepository.findByIsbn13("9781234567890")).willReturn(Optional.empty());
            given(aladinApiClient.lookupByIsbn("9781234567890")).willReturn(aladinResponse);
            given(bookRepository.save(any(Book.class))).willReturn(book);

            BookDetailResponse response = bookService.getBookByIsbn("9781234567890", null);

            assertThat(response).isNotNull();
            then(bookRepository).should().save(any(Book.class));
        }

        @Test
        @DisplayName("BOOK_NOT_FOUND 예외 - Aladin에도 없는 책")
        void Aladin에도_없음_예외() {
            AladinSearchResponse emptyResponse = new AladinSearchResponse();
            emptyResponse.setItems(List.of());

            given(bookRepository.findByIsbn13("0000000000000")).willReturn(Optional.empty());
            given(aladinApiClient.lookupByIsbn("0000000000000")).willReturn(emptyResponse);

            assertThatThrownBy(() -> bookService.getBookByIsbn("0000000000000", null))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.BOOK_NOT_FOUND);
        }

        @Test
        @DisplayName("성공 - 로그인, 서재 포함 여부 확인")
        void 성공_로그인_서재_확인() {
            given(bookRepository.findByIsbn13("9781234567890")).willReturn(Optional.of(book));
            given(memberRepository.findByMemberUserId(1L)).willReturn(Optional.of(member));
            given(libraryRepository.existsByMemberAndBook_BookId(member, 10L)).willReturn(true);

            BookDetailResponse response = bookService.getBookByIsbn("9781234567890", 1L);

            assertThat(response.getInMyLibrary()).isTrue();
        }
    }

    @Nested
    @DisplayName("도서별 감상 목록")
    class GetBookReviews {

        @Test
        @DisplayName("성공 - sort=latest, 로그인 (좋아요 여부 포함)")
        void 성공_latest_로그인() {
            BookReviewSearchRequest request = new BookReviewSearchRequest();
            request.setSort("latest");
            request.setLimit(10);

            given(bookRepository.existsById(10L)).willReturn(true);
            given(reviewRepository.findBookReviewsLatest(eq(10L), isNull(), any(PageRequest.class))).willReturn(List.of(review));
            given(reviewLikeRepository.findLikedReviewIds(List.of(50L), 1L)).willReturn(Set.of(50L));

            BookReviewListResponse response = bookService.getBookReviews(10L, request, 1L);

            assertThat(response.getContent()).hasSize(1);
            assertThat(response.getContent().get(0).getIsLiked()).isTrue();
        }

        @Test
        @DisplayName("성공 - sort=popular")
        void 성공_popular() {
            BookReviewSearchRequest request = new BookReviewSearchRequest();
            request.setSort("popular");
            request.setLimit(10);

            given(bookRepository.existsById(10L)).willReturn(true);
            given(reviewRepository.findBookReviewsPopular(eq(10L), isNull(), isNull(), any(PageRequest.class))).willReturn(List.of(review));
            given(reviewLikeRepository.findLikedReviewIds(List.of(50L), 1L)).willReturn(Set.of());

            BookReviewListResponse response = bookService.getBookReviews(10L, request, 1L);

            assertThat(response.getContent()).hasSize(1);
            then(reviewRepository).should(never()).findBookReviewsLatest(any(), any(), any());
        }

        @Test
        @DisplayName("성공 - 비회원 (좋아요 여부 조회 생략)")
        void 성공_비회원() {
            BookReviewSearchRequest request = new BookReviewSearchRequest();
            request.setSort("latest");
            request.setLimit(10);

            given(bookRepository.existsById(10L)).willReturn(true);
            given(reviewRepository.findBookReviewsLatest(eq(10L), isNull(), any(PageRequest.class))).willReturn(List.of(review));

            BookReviewListResponse response = bookService.getBookReviews(10L, request, null);

            assertThat(response.getContent()).hasSize(1);
            assertThat(response.getContent().get(0).getIsLiked()).isFalse();
            then(reviewLikeRepository).should(never()).findLikedReviewIds(any(), any());
        }

        @Test
        @DisplayName("BOOK_NOT_FOUND 예외")
        void 책_없음_예외() {
            BookReviewSearchRequest request = new BookReviewSearchRequest();
            request.setSort("latest");
            request.setLimit(10);

            given(bookRepository.existsById(10L)).willReturn(false);

            assertThatThrownBy(() -> bookService.getBookReviews(10L, request, null))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.BOOK_NOT_FOUND);
        }
    }
}
