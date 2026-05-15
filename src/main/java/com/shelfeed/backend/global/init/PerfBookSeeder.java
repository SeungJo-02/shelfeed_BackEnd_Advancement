package com.shelfeed.backend.global.init;

import com.shelfeed.backend.domain.book.entity.Book;
import com.shelfeed.backend.domain.book.repository.BookRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@Profile("perf-seed")
@RequiredArgsConstructor
public class PerfBookSeeder implements CommandLineRunner {

    private final BookRepository bookRepository;

    private static final int TARGET_COUNT = 1_000_000;
    private static final int BATCH_SIZE   = 1_000;

    private static final String[] KEYWORDS = {
        "소설", "자기계발", "역사", "과학", "철학",
        "에세이", "시집", "만화", "요리", "여행",
        "심리학", "경제", "경영", "수학", "물리",
        "국어", "영어", "일본어", "중국어", "코딩"
    };

    @Override
    public void run(String... args) throws Exception {
        long count = bookRepository.count();
        if (count >= TARGET_COUNT) {
            log.info("[PerfBookSeeder] 이미 {}건 존재, 시딩 생략", count);
            return;
        }

        long remaining = TARGET_COUNT - count;
        log.info("[PerfBookSeeder] 시딩 시작 (기존: {}건, 추가: {}건)", count, remaining);
        long start = System.currentTimeMillis();

        List<Book> batch = new ArrayList<>(BATCH_SIZE);
        int seeded = 0;

        for (long i = 0; i < remaining; i++) {
            long idx     = count + i;
            String keyword = KEYWORDS[(int)(idx % KEYWORDS.length)];
            String isbn    = String.format("9%012d", idx);
            String title   = keyword + " 명작 " + idx + "편";

            Book book = Book.create(
                isbn, title, "시드저자" + (idx % 100), "시드출판사",
                null, "Performance test book " + idx, 300,
                LocalDate.of(2020 + (int)(idx % 5), (int)(idx % 12) + 1, 1),
                String.valueOf(idx),
                "국내도서>" + keyword + ">테스트",
                keyword
            );
            batch.add(book);

            if (batch.size() == BATCH_SIZE) {
                bookRepository.saveAll(batch);
                batch.clear();
                seeded += BATCH_SIZE;
                if (seeded % 10_000 == 0) {
                    log.info("[PerfBookSeeder] {}건 완료...", seeded);
                }
            }
        }

        if (!batch.isEmpty()) {
            bookRepository.saveAll(batch);
            seeded += batch.size();
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("[PerfBookSeeder] 시딩 완료: {}건, {}ms", seeded, elapsed);
    }
}
