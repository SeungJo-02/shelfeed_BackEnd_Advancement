package com.shelfeed.backend.domain.search.service;

import com.shelfeed.backend.domain.block.repository.BlockRepository;
import com.shelfeed.backend.domain.book.entity.Book;
import com.shelfeed.backend.domain.book.repository.BookRepository;
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
import com.shelfeed.backend.global.redis.RedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    private final BookService bookService;
    private final FollowRepository followRepository;
    private final SearchHistoryRepository searchHistoryRepository;
    private final BlockRepository blockRepository;
    private final RedisService redisService;

    private static final Set<String> VALID_SEARCH_TYPES = Set.of("all", "book", "user");

    //통합 검색
    @Transactional
    public SearchResponse search (String query, String type, Long cursor, int limit, Long memberUserId){
        //쿼리 여부 확인
        if (query == null || query.isBlank()) {
            throw new BusinessException(ErrorCode.SEARCH_QUERY_REQUIRED);
        }
        //타입 유효성 확인
        if (type == null || !VALID_SEARCH_TYPES.contains(type)) {
            throw new BusinessException(ErrorCode.INVALID_SEARCH_TYPE);
        }
        //로그인 시 검색 기록 저장 — 동일 키워드 재검색 시 createdAt 갱신(upsert)
        if (memberUserId != null) {
            Member member = memberLoader.getOrThrow(memberUserId);
            searchHistoryRepository.findByMemberAndKeyword(member, query.trim())
                    .ifPresentOrElse(SearchHistory::touch,
                            () -> searchHistoryRepository.save(SearchHistory.create(member, query.trim()))
                    );
        }

        SearchPageResponse<BookSearchResult> books = SearchPageResponse.empty();
        SearchPageResponse<UserSearchResult> users = SearchPageResponse.empty();

        if (type.equals("all") || type.equals("book")) { books = searchBooks(query, cursor, limit); }
        if (type.equals("all") || type.equals("user")) { users = searchUsers(query, cursor, limit, memberUserId); }

        return SearchResponse.builder()
                .books(books)
                .users(users)
                .build();
    }

    // 도서 검색
    private SearchPageResponse<BookSearchResult> searchBooks(String query, Long cursor, int limit) {
        // 첫 페이지일 때만 알라딘 캐싱 — 신규 키워드도 즉시 결과 노출
        // 알라딘 API 오류·타임아웃 시 DB 결과로 폴백 (검색 자체는 실패하지 않음)
        if (cursor == null && !redisService.isAladinQuerySynced(query)) {
            try {
                bookService.syncFromAladin(query, limit);
                redisService.markAladinQuerySynced(query, 5);
            } catch (Exception e) {
                log.warn("알라딘 캐싱 실패, DB 결과로 폴백: query={}, error={}", query, e.getMessage());
            }
        }
        List<Book> books = bookRepository.searchBooks(query, cursor, PageRequest.of(0, limit + 1));
        // 페이지네이션 처리
        boolean hasNext = books.size() > limit;
        List<Book> result = hasNext ? books.subList(0, limit) : books;

        // 결과가 비어있으면 불필요한 IN 쿼리를 날리지 않음
        if (result.isEmpty()) {
            return SearchPageResponse.empty();
        }

        //IN 절로 통계 데이터 한 번에 조회
        List<Object[]> stats = bookRepository.findReviewStatsByBooks(result);

        // O(1) 조회를 위해 Map으로 변환
        Map<Long, Object[]> statsMap = stats.stream()
                .collect(Collectors.toMap(
                        stat -> (Long) stat[0], // 배열의 0번째 인덱스: bookId
                        stat -> stat            // 배열 전체를 Value로 저장
                ));

        //메모리 내에서 매핑 작업 수행
        List<BookSearchResult> content = result.stream().map(book -> {
            Long bookId = book.getBookId();

            // Map에서 해당 도서의 통계 데이터를 꺼냄 (리뷰가 아예 없는 책은 null일 수 있음)
            Object[] stat = statsMap.get(bookId);

            // DB에서 리뷰가 없어 통계 결과가 없는 경우 기본값 처리 (평점 0.0, 리뷰수 0)
            Double avgRating = (stat != null && stat[1] != null) ? (Double) stat[1] : 0.0;
            Long reviewCount = (stat != null && stat[2] != null) ? (Long) stat[2] : 0L;

            return BookSearchResult.of(book, avgRating, reviewCount);
        }).toList();

        Long nextCursor = hasNext ? result.get(result.size() - 1).getBookId() : null;

        return SearchPageResponse.<BookSearchResult>builder()
                .content(content)
                .nextCursor(nextCursor)
                .hasNext(hasNext)
                .size(content.size())
                .build();
    }

    // 유저 검색
    private SearchPageResponse<UserSearchResult> searchUsers(String query, Long cursor,
                                                             int limit, Long memberUserId) {
        List<Member> members = memberRepository.searchMembers(query, cursor, PageRequest.of(0, limit + 1));
        //페이지네이션
        boolean hasNext = members.size() > limit;
        List<Member> result = hasNext ? members.subList(0, limit) : members;
        //내가 팔로잉을 했는가
        Set<Long> followingIds = Set.of();
        if (memberUserId != null && !result.isEmpty()) {
            Member me = memberLoader.getOrThrow(memberUserId);
            Set<Long> blocked = new HashSet<>(blockRepository.findBlockedIds(me));
            blocked.addAll(blockRepository.findBlockingIds(me));
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
    }

}
