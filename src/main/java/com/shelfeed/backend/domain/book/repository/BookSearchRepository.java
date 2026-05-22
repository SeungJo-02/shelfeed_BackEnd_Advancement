package com.shelfeed.backend.domain.book.repository;

import com.shelfeed.backend.domain.book.document.BookDocument;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.annotations.Query;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

import java.util.List;

public interface BookSearchRepository extends ElasticsearchRepository<BookDocument, Long> {

    // cursor: 다음 페이지 요청 시 마지막 bookId보다 작은 도서만 조회 (cursor=null이면 Long.MAX_VALUE 전달)
    // Pageable의 Sort(bookId DESC)로 cursor 기반 무한 스크롤 보장
    // operator: "and" — Nori가 검색어를 음절 단위로 쪼개도 모든 토큰이 한 필드에 모두 매칭돼야 함
    // ex) "아틀" → ["아","틀"]: title에 "아"와 "틀" 둘 다 있거나, author에 둘 다 있는 책만 매칭
    @Query("""
            {
              "bool": {
                "should": [
                  { "match": { "title":  { "query": "?0", "boost": 2.0, "operator": "and" } } },
                  { "match": { "author": { "query": "?0", "boost": 1.0, "operator": "and" } } }
                ],
                "minimum_should_match": 1,
                "filter": [
                  { "range": { "bookId": { "lt": ?1 } } }
                ]
              }
            }
            """)
    List<BookDocument> searchByTitleOrAuthor(String query, Long cursor, Pageable pageable);
}
