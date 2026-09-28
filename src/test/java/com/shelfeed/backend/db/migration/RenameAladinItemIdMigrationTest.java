package com.shelfeed.backend.db.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V2__rename_aladin_item_id 의 세 분기를 실제 MySQL 에서 검증한다.
 *
 * <p>공유 컨테이너({@code TestContainerSupport})는 이미 Flyway 가 적용된 상태라 "옛 스키마"를 만들 수 없어
 * 전용 컨테이너를 쓴다. 상태별로 DB 를 새로 만들고 V1 을 실행한 뒤 컬럼을 되돌려 옛 덤프를 흉내 낸다.
 * {@code flyway_schema_history} 는 만들지 않으므로 baseline-on-migrate 경로(V1 건너뜀 → V2 부터)가 그대로 탄다.
 */
@DisplayName("Flyway V2: books.aladin_item_id → external_item_id 보정")
class RenameAladinItemIdMigrationTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withUsername("root").withPassword("root");

    @BeforeAll
    static void start() { MYSQL.start(); }

    @AfterAll
    static void stop() { MYSQL.stop(); }

    @Test
    @DisplayName("옛 덤프(aladin_item_id 만 있음): RENAME 되고 값이 보존된다")
    void renamesLegacyColumn() throws Exception {
        String db = "legacy_a";
        prepareLegacySchema(db, false);
        exec(db, "INSERT INTO books (created_at,updated_at,isbn13,author,title,aladin_item_id) "
                + "VALUES (NOW(),NOW(),'9788937460449','헤르만 헤세','데미안','176787')");

        Flyway flyway = migrate(db);

        assertThat(columns(db)).containsExactly("external_item_id");
        assertThat(query(db, "SELECT external_item_id FROM books WHERE isbn13='9788937460449'")).containsExactly("176787");
        assertHistoryAndNoRoutine(db, flyway);
    }

    @Test
    @DisplayName("두 컬럼 공존(ddl-auto=update 가 먼저 돈 DB): 값을 옮기고 옛 컬럼을 DROP 한다")
    void mergesAndDropsWhenBothExist() throws Exception {
        String db = "legacy_b";
        prepareLegacySchema(db, true);
        exec(db, "INSERT INTO books (created_at,updated_at,isbn13,author,title,aladin_item_id,external_item_id) VALUES "
                + "(NOW(),NOW(),'9788937460449','헤르만 헤세','데미안','176787',NULL),"
                + "(NOW(),NOW(),'9791164453115','헤르만 헤세','초판본 데미안','999','already-set')");

        Flyway flyway = migrate(db);

        assertThat(columns(db)).containsExactly("external_item_id");
        assertThat(query(db, "SELECT external_item_id FROM books ORDER BY isbn13"))
                .containsExactly("176787", "already-set"); // NULL 이던 행만 채우고, 이미 있던 값은 덮어쓰지 않는다
        assertHistoryAndNoRoutine(db, flyway);
    }

    @Test
    @DisplayName("새 DB(V1 부터 적용): V2·V3 는 no-op 이고 검증만 통과한다")
    void noopOnFreshSchema() throws Exception {
        String db = "fresh";
        exec(null, "CREATE DATABASE " + db + " CHARACTER SET utf8mb4");

        Flyway flyway = migrate(db);

        assertThat(columns(db)).containsExactly("external_item_id");
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("3");
        assertThat(query(db, "SELECT COUNT(*) FROM genres")).containsExactly("15");
        assertThat(query(db, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='" + db
                + "' AND table_name='member_user_id_seq'")).containsExactly("1");
    }

    // ---- helpers ----

    /** V1 스키마를 만들고 external_item_id 를 aladin_item_id 로 되돌린다. bothColumns 면 빈 external_item_id 도 추가. */
    private static void prepareLegacySchema(String db, boolean bothColumns) throws Exception {
        exec(null, "CREATE DATABASE " + db + " CHARACTER SET utf8mb4");
        String v1 = new String(RenameAladinItemIdMigrationTest.class.getResourceAsStream("/db/migration/V1__baseline.sql")
                .readAllBytes(), StandardCharsets.UTF_8);
        try (Connection con = connect(db); Statement st = con.createStatement()) {
            for (String stmt : v1.split(";\\s*\\n")) {
                String sql = stmt.replaceAll("(?m)^--.*$", "").trim();
                if (!sql.isEmpty()) st.execute(sql);
            }
        }
        exec(db, "ALTER TABLE books RENAME COLUMN external_item_id TO aladin_item_id");
        if (bothColumns) exec(db, "ALTER TABLE books ADD COLUMN external_item_id varchar(50) NULL");
    }

    private static Flyway migrate(String db) {
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl(db), "root", "root")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .load();
        flyway.migrate();
        return flyway;
    }

    private static void assertHistoryAndNoRoutine(String db, Flyway flyway) throws Exception {
        MigrationInfo current = flyway.info().current();
        assertThat(current.getVersion().getVersion()).isEqualTo("3");
        List<String> applied = new ArrayList<>();
        for (MigrationInfo mi : flyway.info().applied()) applied.add(mi.getDescription());
        assertThat(applied).contains("<< Flyway Baseline >>", "rename aladin item id", "ensure member user id seq", "genres seed");
        assertThat(query(db, "SELECT COUNT(*) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA='" + db + "'"))
                .containsExactly("0");
        assertThat(query(db, "SELECT COUNT(*) FROM genres")).containsExactly("15");
    }

    private static List<String> columns(String db) throws Exception {
        return query(db, "SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='" + db
                + "' AND TABLE_NAME='books' AND COLUMN_NAME LIKE '%item_id' ORDER BY COLUMN_NAME");
    }

    private static List<String> query(String db, String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection con = connect(db); Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    private static void exec(String db, String sql) throws Exception {
        try (Connection con = connect(db); Statement st = con.createStatement()) { st.execute(sql); }
    }

    private static Connection connect(String db) throws Exception {
        return DriverManager.getConnection(jdbcUrl(db), "root", "root");
    }

    private static String jdbcUrl(String db) {
        String base = MYSQL.getJdbcUrl(); // jdbc:mysql://host:port/test
        String root = base.substring(0, base.lastIndexOf('/') + 1);
        return root + (db == null ? "" : db) + "?allowMultiQueries=false&useSSL=false&allowPublicKeyRetrieval=true";
    }
}
