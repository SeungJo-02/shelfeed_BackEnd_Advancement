package com.shelfeed.backend.global.init;

import com.shelfeed.backend.domain.book.document.BookDocument;
import com.shelfeed.backend.domain.book.entity.Book;
import com.shelfeed.backend.domain.book.repository.BookRepository;
import com.shelfeed.backend.domain.book.repository.BookSearchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@Order(100)
@Profile({"local", "perf-seed"})
@RequiredArgsConstructor
public class BookIndexInitializer implements ApplicationRunner {

    private final BookRepository bookRepository;
    private final BookSearchRepository bookSearchRepository;
    private final ElasticsearchOperations elasticsearchOperations;

    private static final int BATCH_SIZE = 5000;

    @Override
    public void run(ApplicationArguments args) {
        // 인덱스 미존재 시 Nori 분석기 설정 + 매핑 적용해 생성 (@Document createIndex=false 대응)
        IndexOperations indexOps = elasticsearchOperations.indexOps(BookDocument.class);
        if (!indexOps.exists()) {
            indexOps.createWithMapping();
            log.info("[BookIndexInitializer] ES 인덱스 생성: books (Nori 분석기 적용)");
        }

        long esCount = bookSearchRepository.count();
        long dbCount = bookRepository.count();

        if (esCount >= dbCount) {
            log.info("[BookIndexInitializer] ES 이미 동기화됨 (ES={}, DB={}) — 색인 생략",
                    esCount, dbCount);
            return;
        }

        log.info("[BookIndexInitializer] 색인 시작 (DB={}, ES={})", dbCount, esCount);
        long start = System.currentTimeMillis();
        int totalIndexed = 0;

        int page = 0;
        Slice<Book> slice;
        do {
            slice = bookRepository.findAll(PageRequest.of(page, BATCH_SIZE));
            List<BookDocument> docs = slice.getContent().stream()
                    .map(BookDocument::from)
                    .toList();

            if (!docs.isEmpty()) {
                bookSearchRepository.saveAll(docs);
                totalIndexed += docs.size();
                log.info("[BookIndexInitializer] {}건 색인 완료 (누적)", totalIndexed);
            }
            page++;
        } while (slice.hasNext());

        long elapsed = System.currentTimeMillis() - start;
        log.info("[BookIndexInitializer] 색인 완료: {}건, {}ms", totalIndexed, elapsed);
    }
}
