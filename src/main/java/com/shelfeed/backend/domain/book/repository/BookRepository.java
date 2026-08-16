package com.shelfeed.backend.domain.book.repository;

import com.shelfeed.backend.domain.book.entity.Book;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BookRepository extends JpaRepository<Book, Long> {

    Optional<Book> findByIsbn13(String isbn13); //isbn바코드 조회 확인

    /**
     * 주어진 도서들의 장르 빈도 집계 (내림차순).
     * 서재↔도서 조인이 서비스 경계를 넘게 되어, 서재에서 bookId를 먼저 뽑고 여기서 집계하는 2단계로 나눴다.
     */
    @Query("""
        SELECT b.genre, COUNT(b) as cnt
        FROM Book b
        WHERE b.bookId IN :bookIds
        AND b.genre IS NOT NULL
        GROUP BY b.genre
        ORDER BY cnt DESC
    """)
    List<Object[]> countGenresByBookIds(@Param("bookIds") Collection<Long> bookIds, Pageable pageable);
    //도서의 평균 평점 쿼리
    @Query("""
SELECT AVG(r.rating) FROM Review r WHERE r.book.bookId = :bookId AND r.isDeleted = false
            AND r.reviewVisibility = 'PUBLIC' AND r.reviewStatus = 'PUBLISHED'""")
    Double findAverageRatingByBookId(@Param("bookId") Long bookId);

    // 도서의 감상 수 쿼리
    @Query("""
            SELECT COUNT(r) FROM Review r WHERE r.book.bookId = :bookId AND r.isDeleted = false
            AND r.reviewVisibility = 'PUBLIC' AND r.reviewStatus = 'PUBLISHED'
""")
    Long countReviewsByBookId(@Param("bookId") Long bookId);

    //도서 검색 쿼리
    @Query("""
    SELECT b FROM Book b
    WHERE (b.title LIKE %:query% OR b.author LIKE %:query%)
    AND (:cursor IS NULL OR b.bookId < :cursor)
    ORDER BY b.bookId DESC
""")// TODO: %LIKE% 풀스캔 → Full-Text Search 또는 Elasticsearch로 교체
    List<Book> searchBooks(@Param("query") String query,
                           @Param("cursor") Long cursor,
                           Pageable pageable);

    /**
     * 장르의 알라딘 카테고리 패턴에 걸리는 도서를 페이지로 조회한다.
     *
     * <p>{@code category}에는 알라딘 카테고리 경로가 그대로 들어 있다
     * (예: {@code 국내도서>만화/라이트노벨>일본만화}). {@code genres.category_pattern}이
     * 그 경로의 한 마디와 같은 형태라 부분 문자열로 맞추면 해당 장르의 책이 걸린다.
     *
     * <p>장르명을 검색어로 알라딘에 질의하던 방식보다 훨씬 정확하다 — '만화/라이트노벨'을
     * 검색어로 넣으면 제목에 그 낱말이 든 2권만 나오지만, 카테고리로 맞추면 301권이 걸린다.
     *
     * <p>정렬을 bookId 역순으로 고정해 페이지 경계에서 책이 새거나 겹치지 않게 한다.
     *
     * <p>패턴을 정규식으로 다루는 이유: 알라딘에 '장르소설'이라는 마디가 없다. 그 장르는
     * {@code 판타지/환상문학}·{@code 과학소설(SF)}·{@code 호러.공포소설}·{@code 추리/미스터리소설}
     * 넷으로 흩어져 있어 공통 부분문자열이 없다. {@code |}로 이어 붙인 패턴을 받으려면
     * LIKE로는 안 되고 REGEXP가 필요하다. 마디가 하나뿐인 장르는 그냥 부분일치로 동작한다.
     */
    @Query(value = """
            SELECT * FROM books
            WHERE category REGEXP :pattern
            ORDER BY book_id DESC
            """,
            countQuery = "SELECT COUNT(*) FROM books WHERE category REGEXP :pattern",
            nativeQuery = true)
    Page<Book> findByCategoryPattern(@Param("pattern") String pattern, Pageable pageable);

    // ISBN 목록으로 일괄 조회 (N+1 방지)
    List<Book> findByIsbn13In(List<String> isbn13List);

    // 도서 목록의 평점/리뷰수 일괄 조회 (N+1 방지)
    @Query("""
    SELECT r.book.bookId, AVG(r.rating), COUNT(r)
    FROM Review r
    WHERE r.book IN :books
    AND r.isDeleted = false
    AND r.reviewVisibility = 'PUBLIC'
    AND r.reviewStatus = 'PUBLISHED'
    GROUP BY r.book.bookId
""")
    List<Object[]> findReviewStatsByBooks(@Param("books") List<Book> books);
}
