package com.shelfeed.backend.support;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

/**
 * 통합 테스트가 함께 쓰는 MySQL 컨테이너.
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
 * <p>컨테이너를 하나만 쓰므로 모든 테스트가 같은 DB를 공유한다. 각 테스트는 트랜잭션 롤백이나
 * {@code @BeforeEach}의 명시적 삭제로 스스로 격리해야 한다.
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

    @DynamicPropertySource
    static void overrideDataSourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }
}
