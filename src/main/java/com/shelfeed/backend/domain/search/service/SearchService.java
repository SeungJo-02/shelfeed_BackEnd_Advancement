package com.shelfeed.backend.domain.search.service;

import com.shelfeed.backend.domain.block.service.BlockService;
import com.shelfeed.backend.domain.book.document.BookDocument;
import com.shelfeed.backend.domain.book.entity.Book;
import com.shelfeed.backend.domain.book.repository.BookRepository;
import com.shelfeed.backend.domain.book.repository.BookSearchRepository;
import com.shelfeed.backend.domain.book.service.BookService;
import com.shelfeed.backend.domain.follow.repository.FollowRepository;
import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.member.repository.MemberRepository;
import com.shelfeed.backend.domain.search.dto.response.BookSearchResult;
import com.shelfeed.backend.global.common.helper.MemberLoader;
import com.shelfeed.backend.domain.search.dto.response.SearchPageResponse;
import com.shelfeed.backend.domain.search.dto.response.SearchResponse;
import com.shelfeed.backend.domain.search.dto.response.UserSearchResult;
import com.shelfeed.backend.domain.search.entity.SearchHistory;
import com.shelfeed.backend.domain.search.repository.SearchHistoryRepository;
import com.shelfeed.backend.global.common.exception.BusinessException;
import com.shelfeed.backend.global.common.exception.ErrorCode;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import com.shelfeed.backend.global.redis.RedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SearchService {

    private final MemberRepository memberRepository;
    private final MemberLoader memberLoader;
    private final BookRepository bookRepository;
    private final BookSearchRepository bookSearchRepository;
    private final BookService bookService;
    private final FollowRepository followRepository;
    private final SearchHistoryRepository searchHistoryRepository;
    private final BlockService blockService;
    private final RedisService redisService;
    private final Tracer tracer;

    @Value("${app.search.history-enabled:true}")
    private boolean historyEnabled;

    // ES 사용 여부. false(또는 ES 예외)면 DB LIKE 검색으로 폴백한다.
    // ES 복구 후 true로 되돌리면 다시 ES 역인덱스 검색을 사용한다. (ES 코드는 보존)
    @Value("${app.search.es-enabled:true}")
    private boolean esEnabled;

    private static final Set<String> VALID_SEARCH_TYPES = Set.of("all", "book", "user");

    //통합 검색
    @Transactional
    public SearchResponse search (String query, String type, Long cursor, Long bookCursor, Long userCursor, int limit, Long memberUserId){
        Span span = tracer.nextSpan().name("search").start();
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
        //쿼리 여부 확인
        if (query == null || query.isBlank()) {
            throw new BusinessException(ErrorCode.SEARCH_QUERY_REQUIRED);
        }
        //타입 유효성 확인
        if (type == null || !VALID_SEARCH_TYPES.contains(type)) {
            throw new BusinessException(ErrorCode.INVALID_SEARCH_TYPE);
        }
        //로그인 시 검색 기록 저장 — 동일 키워드 재검색 시 createdAt 갱신(upsert)
        if (memberUserId != null && historyEnabled) {
            Span histSpan = tracer.nextSpan().name("search.history").start();
            try (Tracer.SpanInScope hs = tracer.withSpan(histSpan)) {
                // 공개 ID(memberUserId) → PK(memberId) 해석에는 아직 user 도메인 조회가 필요하다.
                // 서비스 분리 후에는 토큰 클레임에서 memberId를 직접 받아 이 조회를 없앤다.
                Long memberId = memberLoader.getOrThrow(memberUserId).getMemberId();
                searchHistoryRepository.findByMemberIdAndKeyword(memberId, query.trim())
                        .ifPresentOrElse(SearchHistory::touch,
                                () -> searchHistoryRepository.save(SearchHistory.create(memberId, query.trim()))
                        );
            } finally {
                histSpan.end();
            }
        }

        SearchPageResponse<BookSearchResult> books = SearchPageResponse.empty();
        SearchPageResponse<UserSearchResult> users = SearchPageResponse.empty();

        // books 커서(bookId)와 users 커서(memberUserId)는 ID 공간이 다르므로 독립 커서로 페이징한다.
        // type=all에선 bookCursor/userCursor를 각각 사용하고, 단일 타입 검색은 하위호환으로 cursor를 폴백한다.
        Long effectiveBookCursor = bookCursor != null ? bookCursor : cursor;
        Long effectiveUserCursor = userCursor != null ? userCursor : cursor;

        if (type.equals("all") || type.equals("book")) { books = searchBooks(query, effectiveBookCursor, limit); }
        if (type.equals("all") || type.equals("user")) { users = searchUsers(query, effectiveUserCursor, limit, memberUserId); }

        return SearchResponse.builder()
                .books(books)
                .users(users)
                .build();
        } finally {
            span.end();
        }
    }

    // 도서 검색 — ES 역인덱스(title/author, Nori) 우선, ES 불가 시 DB LIKE 폴백.
    // app.search.es-enabled=false 이거나 ES 호출 예외 시 자동으로 DB 검색으로 전환한다. (ES 코드는 보존)
    public SearchPageResponse<BookSearchResult> searchBooks(String query, Long cursor, int limit) {
        Span span = tracer.nextSpan().name("search.books").start();
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
        // 첫 페이지일 때만 알라딘 캐싱 — 신규 키워드도 즉시 결과 노출 (DB에 upsert)
        // 알라딘이 책을 가져온 경우에만 Redis 마커 찍기 (빈 응답 시엔 다음 요청에서 재시도 가능)
        if (cursor == null && !redisService.isAladinQuerySynced(query)) {
            try {
                boolean fetched = bookService.syncFromAladin(query, limit);
                if (fetched) {
                    redisService.markAladinQuerySynced(query, 5);
                }
            } catch (Exception e) {
                log.warn("알라딘 캐싱 실패, 검색 결과로 폴백: query={}, error={}", query, e.getMessage());
            }
        }

        // ES 사용 가능하면 ES, 아니면(비활성 또는 예외 발생) DB LIKE 폴백 — 어느 경우든 유저 검색은 정상 동작
        if (esEnabled) {
            try {
                return searchBooksViaEs(query, cursor, limit);
            } catch (Exception e) {
                log.warn("ES 도서 검색 실패, DB LIKE 폴백: query={}, error={}", query, e.getMessage());
            }
        }
        return searchBooksViaDb(query, cursor, limit);
        } finally {
            span.end();
        }
    }

    // [ES 경로] title/author 역인덱스 검색 — ES 복구 시 다시 사용된다(삭제 금지).
    // cursor 기반 무한 스크롤 (bookId DESC 정렬, cursor=null이면 Long.MAX_VALUE)
    private SearchPageResponse<BookSearchResult> searchBooksViaEs(String query, Long cursor, int limit) {
        Long effectiveCursor = (cursor == null) ? Long.MAX_VALUE : cursor;
        List<BookDocument> docs = bookSearchRepository.searchByTitleOrAuthor(
                query, effectiveCursor,
                PageRequest.of(0, limit + 1, Sort.by(Sort.Direction.DESC, "bookId")));

        boolean hasNext = docs.size() > limit;
        List<BookDocument> result = hasNext ? docs.subList(0, limit) : docs;
        if (result.isEmpty()) {
            return SearchPageResponse.empty();
        }

        // ES에서 받은 bookId 순서를 보존하면서 DB에서 Book 엔티티 조회
        List<Long> bookIds = result.stream().map(BookDocument::getBookId).toList();
        Map<Long, Book> bookMap = bookRepository.findAllById(bookIds).stream()
                .collect(Collectors.toMap(Book::getBookId, b -> b));
        List<Book> orderedBooks = bookIds.stream()
                .map(bookMap::get)
                .filter(Objects::nonNull)
                .toList();

        // DB 누락 시(ES/DB 정합성 깨짐) content가 limit 미만이면 hasNext도 false로 조정
        hasNext = hasNext && orderedBooks.size() == limit;
        return buildBookPage(orderedBooks, hasNext);
    }

    // [DB 폴백 경로] title/author LIKE 검색 — ES 미가용 시 사용.
    // bookId DESC + cursor 페이징으로 ES 경로와 동일한 응답 형태를 유지한다.
    private SearchPageResponse<BookSearchResult> searchBooksViaDb(String query, Long cursor, int limit) {
        List<Book> books = bookRepository.searchBooks(query, cursor, PageRequest.of(0, limit + 1));
        boolean hasNext = books.size() > limit;
        List<Book> orderedBooks = hasNext ? books.subList(0, limit) : books;
        if (orderedBooks.isEmpty()) {
            return SearchPageResponse.empty();
        }
        return buildBookPage(orderedBooks, hasNext);
    }

    // 공통: Book 목록에 통계(평점/리뷰수)를 결합해 커서 페이지 응답을 만든다. (ES/DB 경로 공유)
    private SearchPageResponse<BookSearchResult> buildBookPage(List<Book> orderedBooks, boolean hasNext) {
        // 통계는 항상 DB에서 조회 (평점/리뷰수는 ES에 색인 안 함)
        List<Object[]> stats = bookRepository.findReviewStatsByBooks(orderedBooks);
        Map<Long, Object[]> statsMap = stats.stream()
                .collect(Collectors.toMap(s -> (Long) s[0], s -> s));

        List<BookSearchResult> content = orderedBooks.stream().map(book -> {
            Object[] stat = statsMap.get(book.getBookId());
            Double avgRating = (stat != null && stat[1] != null) ? (Double) stat[1] : 0.0;
            Long reviewCount = (stat != null && stat[2] != null) ? (Long) stat[2] : 0L;
            return BookSearchResult.of(book, avgRating, reviewCount);
        }).toList();

        Long nextCursor = (hasNext && !orderedBooks.isEmpty())
                ? orderedBooks.get(orderedBooks.size() - 1).getBookId()
                : null;

        return SearchPageResponse.<BookSearchResult>builder()
                .content(content)
                .nextCursor(nextCursor)
                .hasNext(hasNext)
                .size(content.size())
                .build();
    }

    // 유저 검색
    public SearchPageResponse<UserSearchResult> searchUsers(String query, Long cursor,
                                                     int limit, Long memberUserId) {
        Span span = tracer.nextSpan().name("search.users").start();
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
        List<Member> members = memberRepository.searchMembers(query, cursor, PageRequest.of(0, limit + 1));
        //페이지네이션
        boolean hasNext = members.size() > limit;
        List<Member> result = hasNext ? members.subList(0, limit) : members;
        //내가 팔로잉을 했는가
        Set<Long> followingIds = Set.of();
        if (memberUserId != null && !result.isEmpty()) {
            Member me = memberLoader.getOrThrow(memberUserId);
            Set<Long> blocked = blockService.blockedIdSet(me);
            if (!blocked.isEmpty()) {
                result = result.stream()
                        .filter(m -> !blocked.contains(m.getMemberUserId()))//차단 된놈 거르고 나머지를 모은다
                        .collect(Collectors.toList());
            }
            if (!result.isEmpty()) {
                followingIds = followRepository.findFollowingIds(me, result);
            }
        }

        final Set<Long> finalFollowingIds = followingIds;
        List<UserSearchResult> content = result.stream()
                .map(target -> UserSearchResult.of(target, finalFollowingIds.contains(target.getMemberUserId())))
                .toList();

        Long nextCursor = (hasNext && !result.isEmpty()) ? result.get(result.size() - 1).getMemberUserId() : null;

        return SearchPageResponse.<UserSearchResult>builder()
                .content(content)
                .nextCursor(nextCursor)
                .hasNext(hasNext)
                .size(content.size())
                .build();
        } finally {
            span.end();
        }
    }

}
