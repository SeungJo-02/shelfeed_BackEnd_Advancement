package com.shelfeed.backend.domain.book.repository;

import com.shelfeed.backend.domain.book.entity.Book;
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
