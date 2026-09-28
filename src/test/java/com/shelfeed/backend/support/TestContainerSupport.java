package com.shelfeed.backend.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Statement;
import java.util.List;

/**
 * 통합 테스트가 함께 쓰는 MySQL 컨테이너. 현재 다섯 클래스(book·comment·genre·member·review)가 상속한다.
 *
 * <p>컨테이너를 정적 초기화로 직접 한 번만 띄우고 JVM이 끝날 때까지 살려 둔다. 정리는
 * Testcontainers의 Ryuk 컨테이너가 JVM 종료 시 맡는다.
 *
 * <p>예전에는 {@code @Testcontainers} + {@code @Container}로 JUnit에 맡겼는데, 그러면 컨테이너가
 * <b>테스트 클래스 단위</b>로 켜지고 꺼진다. 반면 스프링 테스트 컨텍스트는 설정이 같은 클래스끼리
 * <b>캐시해서 재사용</b>한다. 두 수명이 어긋나서, 설정이 같은 클래스가 둘이면 앞 클래스가 끝날 때
 * 컨테이너가 내려가고 뒤 클래스는 그 죽은 컨테이너를 가리키는 캐시된 DataSource를 물려받아
 * {@code Connection is not available} 로 전멸했다. 설정이 다른 클래스끼리는 컨텍스트가 새로 만들어져
 * 우연히 살아남았기 때문에, 같은 설정의 테스트를 하나 더 추가하는 순간 드러나는 함정이었다.
 *
 * <p>컨테이너를 하나만 쓰므로 모든 테스트가 같은 DB를 공유한다. 스키마는 Flyway가 컨텍스트마다가 아니라
 * 컨테이너에 한 번만 만든다(예전 {@code ddl-auto=create-drop}은 컨텍스트마다 테이블을 새로 만들어 앞 클래스의
 * 잔여 행을 지워 줬지만, 이제는 남는다). 그래서 매 테스트 전에 {@link #truncateAllTables()}가
 * {@code flyway_schema_history}를 제외한 모든 테이블을 비워 create-drop 시절과 같은 빈 DB에서 시작하게 한다.
 * FK 순서 문제를 피하려고 검사만 잠시 끄고 TRUNCATE 한다. R__ 장르 시드도 함께 비워지는데, 예전에도 테스트
 * 프로파일에서는 data.sql이 실행되지 않았으므로 동작이 같다.
 */
@ActiveProfiles("test")
public abstract class TestContainerSupport {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("shelfeed_test")
            .withUsername("test")
            .withPassword("test");

    static {
        MYSQL.start();
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void truncateAllTables() {
        List<String> tables = jdbcTemplate.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_type = 'BASE TABLE'
                  AND table_name <> 'flyway_schema_history'
                """, String.class);
        // SET FOREIGN_KEY_CHECKS 는 세션(커넥션) 범위라 TRUNCATE 와 같은 커넥션에서 실행해야 한다.
        // TRUNCATE 는 MySQL 에서 암묵적 커밋이다. @DataJpaTest 만 붙인 하위 클래스(BookRepositoryCategoryTest,
        // CommentRepositoryTopSortTest)는 이 @BeforeEach 를 가로지르는 롤백 보장을 기대하면 안 된다.
        jdbcTemplate.execute((ConnectionCallback<Void>) con -> {
            try (Statement st = con.createStatement()) {
                st.execute("SET FOREIGN_KEY_CHECKS = 0");
                try {
                    for (String table : tables) {
                        st.execute("TRUNCATE TABLE `" + table + "`");
                    }
                } finally {
                    st.execute("SET FOREIGN_KEY_CHECKS = 1");
                }
            }
            return null;
        });
    }

    @DynamicPropertySource
    static void overrideDataSourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }
}
