package com.shelfeed.backend.domain.book;

import com.shelfeed.backend.domain.book.entity.Book;
import com.shelfeed.backend.domain.book.repository.BookRepository;
import com.shelfeed.backend.global.config.JpaConfig;
import com.shelfeed.backend.support.TestContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 장르별 도서 조회의 카테고리 매칭 통합 테스트.
 *
 * <p>{@code REGEXP}는 MySQL 문법이고 네이티브 쿼리라 JPQL 검증이 통하지 않는다.
 * 실제 MySQL에 붙여 확인한다.
 *
 * <p>고정하려는 것은 두 가지다. 마디가 하나뿐인 장르가 예전 LIKE와 똑같이 동작하는지,
 * 그리고 알라딘에 마디가 없어 여러 갈래로 흩어진 '장르소설'이 {@code |}로 이어 붙인
 * 패턴으로 걸리는지.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
@DisplayName("장르 카테고리 패턴 매칭 통합 테스트")
class BookRepositoryCategoryTest extends TestContainerSupport {

    @Autowired BookRepository bookRepository;

    /** 알라딘이 실제로 내려주는 카테고리 경로 형태를 그대로 쓴다. */
    private static final List<String> CATEGORIES = List.of(
            "국내도서>만화/라이트노벨>일본만화",
            "국내도서>만화/라이트노벨>본격장르만화>판타지>액션 판타지",
            "국내도서>소설/시/희곡>영미소설",
            "국내도서>소설/시/희곡>판타지/환상문학>한국판타지/환상소설",
            "국내도서>소설/시/희곡>과학소설(SF)>한국 과학소설",
            "국내도서>소설/시/희곡>호러.공포소설>한국 호러.공포소설",
            "국내도서>소설/시/희곡>추리/미스터리소설>일본 추리/미스터리소설",
            "국내도서>에세이>한국에세이"
    );

    @BeforeEach
    void setUp() {
        bookRepository.deleteAllInBatch();
        for (int i = 0; i < CATEGORIES.size(); i++) {
            bookRepository.save(Book.create(
                    "978000000000" + i, "책 " + i, "저자", "출판사",
                    null, null, 100, null, String.valueOf(i), CATEGORIES.get(i), null));
        }
    }

    private Page<Book> find(String pattern) {
        return bookRepository.findByCategoryPattern(pattern, PageRequest.of(0, 20));
    }

    @Test
    @DisplayName("마디가 하나인 장르는 부분일치로 걸린다")
    void 단일_패턴() {
        assertThat(find("만화/라이트노벨").getTotalElements()).isEqualTo(2);
        assertThat(find("에세이").getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("상위 마디로 찾으면 하위 갈래가 모두 걸린다")
    void 상위_마디() {
        // 소설/시/희곡 아래에 영미소설·판타지·SF·호러·추리가 있다.
        assertThat(find("소설/시/희곡").getTotalElements()).isEqualTo(5);
    }

    @Test
    @DisplayName("장르소설처럼 흩어진 갈래는 | 로 이어 붙여 한 번에 찾는다")
    void 다중_패턴() {
        // 알라딘에 '장르소설' 마디가 없어 넷으로 흩어져 있다.
        Page<Book> result = find("판타지/환상문학|과학소설|호러.공포소설|추리/미스터리소설");

        assertThat(result.getTotalElements()).isEqualTo(4);
        assertThat(result.getContent())
                .extracting(Book::getCategory)
                .allSatisfy(c -> assertThat(c).contains("소설/시/희곡"));
    }

    @Test
    @DisplayName("예전 패턴('장르소설')으로는 아무것도 걸리지 않는다")
    void 기존_패턴은_0건() {
        // 이 사실이 패턴을 바꾼 이유다. 되돌아가면 이 테스트가 깨진다.
        assertThat(find("장르소설").getTotalElements()).isZero();
    }

    @Test
    @DisplayName("페이지 수를 세어 클라이언트가 고를 범위를 알려준다")
    void 페이지_정보() {
        Page<Book> page = bookRepository.findByCategoryPattern("국내도서", PageRequest.of(0, 3));

        assertThat(page.getTotalElements()).isEqualTo(CATEGORIES.size());
        assertThat(page.getTotalPages()).isEqualTo(3);
        assertThat(page.getContent()).hasSize(3);
    }
}
