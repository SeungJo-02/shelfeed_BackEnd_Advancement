package com.shelfeed.backend.global.init;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 규모 시더 (프로파일 {@code scale-seed}).
 *
 * <p>정보나루 수집 CSV(seed/data/books.csv)를 books 에 upsert 한 뒤, 그 위에 회원·장르 선호·팔로우(허브 편향)·
 * 서재·감상·댓글·댓글 좋아요·감상 좋아요·피드·알림을 {@link SeedProperties} 규모로 생성한다. 인덱스·캐시·커넥션 풀
 * 고도화의 실측 전제 데이터다(이슈 #71).
 *
 * <p>모든 대량 INSERT 는 JdbcTemplate batch 로 처리한다. JDBC URL 에 {@code rewriteBatchedStatements=true} 가 있어야
 * 배치가 실제 multi-row INSERT 로 나가며, 그러려면 VALUES 절에 SQL 함수 대신 바인딩 값만 있어야 한다.
 *
 * <p>멱등성: 도서 upsert 는 항상 실행되고(isbn13 기준), 합성 데이터는 members 와 reviews 가 모두 목표치 이상이면 통째로
 * 건너뛴다. 그렇지 않으면 단계별로 자기 테이블 건수를 보고 부족한 단계만 실행한다 — 이전 실행이 중간에 죽었어도
 * 재실행으로 복구되고, 유니크 키 + INSERT IGNORE 로 중복이 생기지 않는다. 카운터 컬럼은 마지막에 COUNT(*) 로 다시
 * 계산해 실제 행 수와 정확히 맞춘다.
 *
 * <p>안전장치: prod 프로파일이거나 datasource 호스트가 로컬(localhost/127.0.0.1/mysql/mysql-perf)이 아니면 즉시 중단한다.
 */
@Slf4j
@Component
@Profile("scale-seed")
@EnableConfigurationProperties(SeedProperties.class)
@RequiredArgsConstructor
public class ScaleSeeder implements CommandLineRunner {

    static final Set<String> ALLOWED_DB_HOSTS = Set.of("localhost", "127.0.0.1", "mysql", "mysql-perf");
    private static final String SAMPLE_CSV = "seed/data/books.sample.csv";
    private static final String SEED_EMAIL_DOMAIN = "@seed.shelfeed.test";
    private static final String[] LIBRARY_STATUSES = {"FINISHED", "READING", "WANT_TO_READ", "STOPPED"};
    private static final int COUNTER_CHUNK = 20_000;
    private static final int MINUTES_30D = 60 * 24 * 30;
    private static final String[] REVIEW_OPENERS = {
            "오랜만에 밤을 새워 읽었다.", "기대 없이 펼쳤는데 끝까지 놓지 못했다.", "문장이 담백해서 좋았다.",
            "중반부가 조금 늘어졌지만 결말이 모든 걸 보상한다.", "다시 읽고 싶은 책.", "추천받고 읽었는데 취향은 아니었다.",
            "밑줄을 너무 많이 그어서 책이 지저분해졌다.", "읽는 내내 마음이 무거웠다.", "가볍게 읽기 좋다."
    };
    private static final String[] COMMENT_TEXTS = {
            "저도 이 책 읽었는데 공감돼요.", "이 부분 정말 좋았죠!", "다음 권도 기대돼요.", "감상 잘 읽었습니다.",
            "저는 결말이 조금 아쉬웠어요.", "덕분에 읽어보고 싶어졌어요.", "밑줄 친 문장 공유해주세요."
    };

    private final JdbcTemplate jdbc;
    private final SeedProperties props;
    private final PasswordEncoder passwordEncoder;
    private final Environment env;
    private final org.springframework.context.ConfigurableApplicationContext ctx;

    @Override
    public void run(String... args) throws Exception {
        guardTarget(env.getActiveProfiles(), env.getProperty("spring.datasource.url", ""));
        long t0 = System.currentTimeMillis();
        log.info("[ScaleSeeder] 시작: {}", props);
        int bookRows = upsertBooks();
        long members = count("members");
        long reviewsNow = count("reviews");
        if (members >= props.getMembers() && reviewsNow >= props.getReviews()) {
            log.info("[ScaleSeeder] members={} ≥ {} 이고 reviews={} ≥ {} → 합성 데이터 생성 생략 (도서 upsert {}행)",
                    members, props.getMembers(), reviewsNow, props.getReviews(), bookRows);
            exitIfRequested();
            return;
        }
        Random rnd = new Random(props.getSeed());
        List<Long> bookIds = jdbc.queryForList("SELECT book_id FROM books ORDER BY book_id", Long.class);
        if (bookIds.isEmpty()) throw new IllegalStateException("books 가 비어 있다. CSV 경로를 확인하라: " + props.getBooksCsv());

        // 각 단계는 자기 테이블 건수로 게이팅한다. 목표의 90% 미만이면 (부분 실패로 본다) 부족분을 다시 채운다.
        List<Long> memberIds = members >= props.getMembers()
                ? seedMemberIds()
                : seedMembers(rnd, (int) (props.getMembers() - members));
        int n = memberIds.size();
        runPhase("member_genres", (long) n * 2, () -> seedMemberGenres(rnd, memberIds));
        runPhase("follows", (long) n * props.getFollowsPerMember() * 9 / 10, () -> seedFollows(rnd, memberIds));
        runPhase("library_books", (long) n * Math.min(props.getLibraryPerMember(), bookIds.size()) * 9 / 10, () -> seedLibraryBooks(rnd, memberIds, bookIds));
        List<long[]> libraryRows = loadLibraryRows(memberIds);
        runPhase("reviews", props.getReviews(), () -> seedReviews(rnd, libraryRows));
        List<long[]> reviews = loadReviews(memberIds);
        long publicReviews = reviews.stream().filter(r -> r[4] == 1).count();
        runPhase("comments", publicReviews * props.getCommentsPerReview() * 9 / 10, () -> seedComments(rnd, memberIds, reviews));
        List<long[]> comments = loadComments(reviews);
        runPhase("comment_likes", comments.size() / 2, () -> seedCommentLikes(rnd, memberIds, comments));
        runPhase("review_likes", publicReviews * props.getLikesPerReview() * 9 / 10, () -> seedReviewLikes(rnd, memberIds, reviews));
        runPhase("feeds", (long) n * props.getFeedsPerMember() / 2, () -> seedFeedsAndFollowingReviewNotifications(rnd, memberIds, reviews));
        runPhase("notifications", (long) n * props.getNotificationsPerMember() / 2, () -> seedNotifications(rnd, memberIds, reviews, comments));
        fixCounters(memberIds, reviews, comments);
        log.info("[ScaleSeeder] 완료: {}초", (System.currentTimeMillis() - t0) / 1000);
        exitIfRequested();
    }

    /** prod 나 원격 DB 를 대상으로 돌지 않게 한다. */
    static void guardTarget(String[] activeProfiles, String datasourceUrl) {
        if (Arrays.asList(activeProfiles).contains("prod")) {
            throw new IllegalStateException("[ScaleSeeder] prod 프로파일에서는 실행할 수 없다: " + Arrays.toString(activeProfiles));
        }
        String host = jdbcHost(datasourceUrl);
        if (!ALLOWED_DB_HOSTS.contains(host)) {
            throw new IllegalStateException("[ScaleSeeder] 로컬 DB 에만 시딩할 수 있다. datasource host=" + host + " (허용: " + ALLOWED_DB_HOSTS + ")");
        }
        log.info("[ScaleSeeder] 대상 DB host={} (허용됨)", host);
    }

    static String jdbcHost(String url) {
        try {
            String rest = url.startsWith("jdbc:") ? url.substring(5) : url;
            String host = URI.create(rest).getHost();
            return host == null ? "" : host.toLowerCase();
        } catch (Exception e) {
            return "";
        }
    }

    private void runPhase(String table, long target, Runnable phase) {
        long have = count(table);
        if (have >= target) { log.info("[ScaleSeeder] {} {} ≥ {} → 생략", table, have, target); return; }
        phase.run();
    }

    // ── 1. 도서 upsert ─────────────────────────────────────────────────
    private int upsertBooks() throws IOException {
        Path csv = Path.of(props.getBooksCsv());
        if (!Files.exists(csv)) {
            log.warn("[ScaleSeeder] {} 없음 → 샘플 {} 사용", csv, SAMPLE_CSV);
            csv = Path.of(SAMPLE_CSV);
        }
        long t = System.currentTimeMillis();
        List<Map<String, String>> rows = readCsv(csv);
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        String sql = """
                INSERT INTO books (isbn13, title, author, publisher, cover_image_url, description, total_pages,
                                   published_date, external_item_id, category, genre, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, NULL, ?, NULL, ?, ?, ?, ?) AS new
                ON DUPLICATE KEY UPDATE title = new.title, author = new.author, publisher = new.publisher,
                    cover_image_url = new.cover_image_url,
                    description = COALESCE(NULLIF(new.description, ''), books.description),
                    published_date = COALESCE(new.published_date, books.published_date),
                    category = COALESCE(NULLIF(new.category, ''), books.category),
                    genre = COALESCE(NULLIF(new.genre, ''), books.genre),
                    updated_at = new.updated_at
                """;
        batch(sql, rows, (ps, r) -> {
            ps.setString(1, r.get("isbn13"));
            ps.setString(2, cut(r.get("title"), 500));
            ps.setString(3, cut(blankTo(r.get("author"), "미상"), 50));
            ps.setString(4, cut(r.get("publisher"), 200));
            ps.setString(5, cut(r.get("cover_url"), 500));
            ps.setString(6, r.get("description"));
            String year = r.get("published_year");
            if (year != null && year.matches("\\d{4}")) ps.setDate(7, java.sql.Date.valueOf(LocalDate.of(Integer.parseInt(year), 1, 1)));
            else ps.setNull(7, Types.DATE);
            ps.setString(8, cut(r.get("category"), 100));
            ps.setString(9, cut(r.get("genre"), 100));
            ps.setTimestamp(10, now); ps.setTimestamp(11, now);
        });
        log.info("[ScaleSeeder] books upsert {}행 ({}ms, source={})", rows.size(), System.currentTimeMillis() - t, csv);
        return rows.size();
    }

    // ── 2. 회원 ────────────────────────────────────────────────────────
    private List<Long> seedMembers(Random rnd, int n) {
        long t = System.currentTimeMillis();
        // 회원 번호 블록을 시퀀스에서 한 번에 확보한다 (MemberUserIdGenerator 와 같은 방식: 같은 커넥션의 LAST_INSERT_ID).
        // 먼저 시퀀스를 실제 최대 회원 번호에 맞춰 끌어올려, 뒤처진 시퀀스가 이미 쓰인 번호를 내주지 않게 한다.
        long end = jdbc.execute((ConnectionCallback<Long>) con -> {
            try (var st = con.createStatement()) {
                st.executeUpdate("INSERT IGNORE INTO member_user_id_seq (id, next_val) SELECT 1, COALESCE(MAX(member_user_id), 0) FROM members");
                st.executeUpdate("UPDATE member_user_id_seq SET next_val = GREATEST(next_val, (SELECT COALESCE(MAX(member_user_id), 0) FROM members)) WHERE id = 1");
            }
            try (PreparedStatement ps = con.prepareStatement("UPDATE member_user_id_seq SET next_val = LAST_INSERT_ID(next_val + ?) WHERE id = 1")) {
                ps.setInt(1, n); ps.executeUpdate();
            }
            try (var st = con.createStatement(); var rs = st.executeQuery("SELECT LAST_INSERT_ID()")) { rs.next(); return rs.getLong(1); }
        });
        long start = end - n + 1;
        String hash = passwordEncoder.encode("Seed1234!");
        LocalDateTime now = LocalDateTime.now();
        String sql = """
                INSERT INTO members (member_user_id, email, password, nickname, bio, profile_image_url, email_verified,
                    onboarding_completed, library_visibility, notification_preferences, follower_count, following_count,
                    review_count, role, status, created_at, updated_at, last_login_at)
                VALUES (?, ?, ?, ?, ?, NULL, 1, 1, ?, ?, 0, 0, 0, 'USER', 'ACTIVE', ?, ?, ?)
                """;
        List<Long> userIds = new ArrayList<>(n);
        for (long u = start; u <= end; u++) userIds.add(u);
        batch(sql, userIds, (ps, u) -> {
            LocalDateTime created = now.minusDays(rnd.nextInt(730)).minusMinutes(rnd.nextInt(1440));
            ps.setLong(1, u);
            ps.setString(2, "seed" + u + SEED_EMAIL_DOMAIN);
            ps.setString(3, hash);
            ps.setString(4, "독서가" + u);
            ps.setString(5, rnd.nextInt(3) == 0 ? null : "책과 커피를 좋아하는 " + u + "번째 회원");
            ps.setString(6, rnd.nextInt(10) == 0 ? "PRIVATE" : "PUBLIC");
            ps.setString(7, notificationPreferences(rnd));
            ps.setTimestamp(8, Timestamp.valueOf(created));
            ps.setTimestamp(9, Timestamp.valueOf(created));
            ps.setTimestamp(10, Timestamp.valueOf(now.minusHours(rnd.nextInt(24 * 30))));
        });
        log.info("[ScaleSeeder] members {}명 (user_id {}~{}, {}ms)", n, start, end, System.currentTimeMillis() - t);
        return seedMemberIds();
    }

    /** 회원마다 알림 설정을 다르게 두어 알림 필터링 경로가 실제로 걸리게 한다 (약 70% 는 전부 켜짐). */
    private static String notificationPreferences(Random rnd) {
        boolean all = rnd.nextInt(10) < 7;
        return "{\"likeEnabled\":" + (all || rnd.nextBoolean()) + ",\"commentEnabled\":" + (all || rnd.nextBoolean())
                + ",\"followEnabled\":" + (all || rnd.nextBoolean()) + ",\"followingReviewEnabled\":" + (all || rnd.nextBoolean()) + "}";
    }

    /** 시드 회원(이메일 도메인 기준) id 목록. 관리자 등 다른 회원은 제외한다. */
    private List<Long> seedMemberIds() {
        return jdbc.queryForList("SELECT member_id FROM members WHERE email LIKE ? ORDER BY member_id", Long.class, "%" + SEED_EMAIL_DOMAIN);
    }

    // ── 3. 장르 선호 ───────────────────────────────────────────────────
    private void seedMemberGenres(Random rnd, List<Long> memberIds) {
        long t = System.currentTimeMillis();
        List<Long> genreIds = jdbc.queryForList("SELECT genre_id FROM genres ORDER BY genre_id", Long.class);
        if (genreIds.isEmpty()) { log.warn("[ScaleSeeder] genres 비어 있음 → 장르 선호 생략"); return; }
        List<long[]> rows = new ArrayList<>();
        for (long m : memberIds) {
            List<Long> shuffled = new ArrayList<>(genreIds);
            Collections.shuffle(shuffled, rnd);
            int k = 2 + rnd.nextInt(3);
            for (int i = 0; i < Math.min(k, shuffled.size()); i++) rows.add(new long[]{m, shuffled.get(i)});
        }
        batch("INSERT IGNORE INTO member_genres (member_id, genre_id) VALUES (?, ?)", rows,
                (ps, r) -> { ps.setLong(1, r[0]); ps.setLong(2, r[1]); });
        log.info("[ScaleSeeder] member_genres {}행 ({}ms)", rows.size(), System.currentTimeMillis() - t);
    }

    // ── 4. 팔로우 (허브 편향) ───────────────────────────────────────────
    private void seedFollows(Random rnd, List<Long> memberIds) {
        long t = System.currentTimeMillis();
        int n = memberIds.size();
        int hubs = Math.max(1, (int) Math.round(n * props.getHubRatio()));
        int hubFollowers = Math.min(props.getHubFollowers(), n - 1);
        LongPairSet seen = new LongPairSet(n * (props.getFollowsPerMember() + 1) + hubs * hubFollowers);
        List<long[]> rows = new ArrayList<>();
        // 허브: 앞의 hubs 명이 hubFollowers 명의 팔로워를 받는다
        for (int h = 0; h < hubs; h++) {
            long hub = memberIds.get(h);
            int made = 0;
            while (made < hubFollowers) {
                int idx = rnd.nextInt(n);
                if (idx == h) continue;
                if (seen.add(memberIds.get(idx), hub)) { rows.add(new long[]{memberIds.get(idx), hub}); made++; }
            }
        }
        // 일반: 평균 followsPerMember 명을 팔로우 (0 ~ 2×평균 균등)
        for (int i = 0; i < n; i++) {
            long follower = memberIds.get(i);
            int k = rnd.nextInt(props.getFollowsPerMember() * 2 + 1);
            for (int j = 0; j < k; j++) {
                long followee = memberIds.get(rnd.nextInt(n));
                if (follower != followee && seen.add(follower, followee)) rows.add(new long[]{follower, followee});
            }
        }
        LocalDateTime now = LocalDateTime.now();
        batch("INSERT IGNORE INTO follows (follower_id, followee_id, created_at) VALUES (?, ?, ?)", rows,
                (ps, r) -> { ps.setLong(1, r[0]); ps.setLong(2, r[1]); ps.setTimestamp(3, Timestamp.valueOf(now.minusHours(rnd.nextInt(24 * 700)))); });
        log.info("[ScaleSeeder] follows {}행 (허브 {}명 × {}팔로워, {}ms)", rows.size(), hubs, hubFollowers, System.currentTimeMillis() - t);
    }

    // ── 5. 서재 (인기 도서 편향) ─────────────────────────────────────────
    private void seedLibraryBooks(Random rnd, List<Long> memberIds, List<Long> bookIds) {
        long t = System.currentTimeMillis();
        int[] weights = {55, 20, 20, 5};
        int perMember = Math.min(props.getLibraryPerMember(), bookIds.size());
        List<long[]> rows = new ArrayList<>();
        LongPairSet seen = new LongPairSet(memberIds.size() * perMember);
        for (long m : memberIds) {
            int made = 0, guard = 0;
            while (made < perMember && guard++ < perMember * 5) {
                long book = bookIds.get(popularityBiased(rnd, bookIds.size()));   // CSV 는 대출 수 내림차순 → 앞쪽 = 인기 도서
                if (seen.add(m, book)) { rows.add(new long[]{m, book, pick(rnd, weights)}); made++; }
            }
        }
        LocalDateTime now = LocalDateTime.now();
        batch("INSERT IGNORE INTO library_books (member_id, book_id, status, started_at, finished_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                rows, (ps, r) -> {
                    String st = LIBRARY_STATUSES[(int) r[2]];
                    LocalDateTime created = now.minusDays(rnd.nextInt(730));
                    ps.setLong(1, r[0]); ps.setLong(2, r[1]); ps.setString(3, st);
                    if (st.equals("WANT_TO_READ")) ps.setNull(4, Types.DATE); else ps.setDate(4, java.sql.Date.valueOf(created.toLocalDate()));
                    if (st.equals("FINISHED")) ps.setDate(5, java.sql.Date.valueOf(created.toLocalDate().plusDays(rnd.nextInt(30)))); else ps.setNull(5, Types.DATE);
                    ps.setTimestamp(6, Timestamp.valueOf(created)); ps.setTimestamp(7, Timestamp.valueOf(created));
                });
        log.info("[ScaleSeeder] library_books {}행 ({}ms)", rows.size(), System.currentTimeMillis() - t);
    }

    /** @return {library_book_id, member_id, book_id, statusOrdinal} */
    private List<long[]> loadLibraryRows(List<Long> memberIds) {
        List<long[]> out = new ArrayList<>();
        jdbc.query("SELECT library_book_id, member_id, book_id, status FROM library_books WHERE member_id BETWEEN ? AND ?",
                (RowCallbackHandler) rs -> out.add(new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3), Arrays.asList(LIBRARY_STATUSES).indexOf(rs.getString(4))}),
                memberIds.get(0), memberIds.get(memberIds.size() - 1));
        return out;
    }

    // ── 6. 감상 ────────────────────────────────────────────────────────
    private void seedReviews(Random rnd, List<long[]> library) {
        long t = System.currentTimeMillis();
        // 감상은 읽는 중/다 읽은 서재 행에만 붙는다. 이미 감상이 달린 행은 제외한다(부분 실패 후 재실행 대비).
        Set<Long> reviewed = new HashSet<>(jdbc.queryForList("SELECT library_book_id FROM reviews WHERE library_book_id IS NOT NULL", Long.class));
        List<long[]> pool = new ArrayList<>();
        for (long[] r : library) if ((r[3] == 0 || r[3] == 1) && !reviewed.contains(r[0])) pool.add(r);
        int need = (int) Math.max(0, props.getReviews() - count("reviews"));
        int target = Math.min(need, pool.size());
        if (pool.size() < need) log.warn("[ScaleSeeder] reviews 부족분 {} > 후보 서재 행(READING/FINISHED, 미감상) {} → {}건만 생성 (library-per-member 를 늘려라)", need, pool.size(), target);
        List<long[]> chosen = new ArrayList<>(pool);
        Collections.shuffle(chosen, rnd);
        chosen = chosen.subList(0, target);
        LocalDateTime now = LocalDateTime.now();
        String sql = """
                INSERT INTO reviews (member_id, book_id, library_book_id, rating, content, quote, read_pages, is_spoiler,
                    review_visibility, review_status, like_count, comment_count, is_deleted, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0, ?, ?)
                """;
        batch(sql, chosen, (ps, r) -> {
            LocalDateTime created = now.minusMinutes(rnd.nextInt(60 * 24 * 730));
            int roll = rnd.nextInt(100);
            String vis = roll < 88 ? "PUBLIC" : roll < 95 ? "FOLLOWER" : "PRIVATE";
            String status = rnd.nextInt(100) < 95 ? "PUBLISHED" : "DRAFT";
            int rating = Math.min(5, 1 + (rnd.nextInt(100) < 70 ? 3 + rnd.nextInt(2) : rnd.nextInt(5)));
            ps.setLong(1, r[1]); ps.setLong(2, r[2]); ps.setLong(3, r[0]);
            ps.setByte(4, (byte) rating);
            ps.setString(5, REVIEW_OPENERS[rnd.nextInt(REVIEW_OPENERS.length)] + " (" + r[2] + "번 도서, 별 " + rating + ")");
            ps.setString(6, rnd.nextInt(3) == 0 ? "가장 오래 남은 문장 하나를 옮겨 적는다." : null);
            if (rnd.nextInt(2) == 0) ps.setInt(7, 50 + rnd.nextInt(400)); else ps.setNull(7, Types.INTEGER);
            ps.setBoolean(8, rnd.nextInt(10) == 0);
            ps.setString(9, vis); ps.setString(10, status);
            ps.setTimestamp(11, Timestamp.valueOf(created)); ps.setTimestamp(12, Timestamp.valueOf(created));
        });
        log.info("[ScaleSeeder] reviews {}행 ({}ms)", chosen.size(), System.currentTimeMillis() - t);
    }

    /** @return {review_id, member_id, book_id, createdEpochMinute, publicPublished(1/0)} */
    private List<long[]> loadReviews(List<Long> memberIds) {
        List<long[]> out = new ArrayList<>();
        jdbc.query("SELECT review_id, member_id, book_id, UNIX_TIMESTAMP(created_at) DIV 60, review_status = 'PUBLISHED' AND review_visibility = 'PUBLIC' FROM reviews WHERE member_id BETWEEN ? AND ?",
                (RowCallbackHandler) rs -> out.add(new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getBoolean(5) ? 1 : 0}),
                memberIds.get(0), memberIds.get(memberIds.size() - 1));
        return out;
    }

    // ── 7. 댓글 ────────────────────────────────────────────────────────
    private void seedComments(Random rnd, List<Long> memberIds, List<long[]> reviews) {
        long t = System.currentTimeMillis();
        Set<Long> hasComment = new HashSet<>(jdbc.queryForList("SELECT DISTINCT review_id FROM comments", Long.class));
        List<long[]> rows = new ArrayList<>();
        for (long[] r : reviews) {
            if (r[4] == 0 || hasComment.contains(r[0])) continue;
            int k = rnd.nextInt(props.getCommentsPerReview() * 2 + 1);
            for (int i = 0; i < k; i++) {
                long m = memberIds.get(rnd.nextInt(memberIds.size()));
                if (m != r[1]) rows.add(new long[]{r[0], m, afterMinute(rnd, r[3])});   // 글쓴이는 자기 글에 댓글을 달지 않는다
            }
        }
        batch("INSERT INTO comments (review_id, member_id, parent_comment_id, content, like_count, is_deleted, created_at, updated_at) VALUES (?, ?, NULL, ?, 0, 0, ?, ?)",
                rows, (ps, r) -> {
                    Timestamp at = minuteTs(r[2]);
                    ps.setLong(1, r[0]); ps.setLong(2, r[1]);
                    ps.setString(3, COMMENT_TEXTS[rnd.nextInt(COMMENT_TEXTS.length)]);
                    ps.setTimestamp(4, at); ps.setTimestamp(5, at);
                });
        log.info("[ScaleSeeder] comments {}행 ({}ms)", rows.size(), System.currentTimeMillis() - t);
    }

    /** @return {comment_id, review_id, member_id(author), review_owner_id, createdEpochMinute} */
    private List<long[]> loadComments(List<long[]> reviews) {
        Map<Long, Long> owner = new HashMap<>();
        for (long[] r : reviews) owner.put(r[0], r[1]);
        List<long[]> out = new ArrayList<>();
        if (reviews.isEmpty()) return out;
        long lo = reviews.stream().mapToLong(r -> r[0]).min().orElse(0), hi = reviews.stream().mapToLong(r -> r[0]).max().orElse(0);
        jdbc.query("SELECT comment_id, review_id, member_id, UNIX_TIMESTAMP(created_at) DIV 60 FROM comments WHERE review_id BETWEEN ? AND ?",
                (RowCallbackHandler) rs -> out.add(new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3), owner.getOrDefault(rs.getLong(2), 0L), rs.getLong(4)}), lo, hi);
        return out;
    }

    // ── 7b. 댓글 좋아요 ────────────────────────────────────────────────
    private void seedCommentLikes(Random rnd, List<Long> memberIds, List<long[]> comments) {
        long t = System.currentTimeMillis();
        List<long[]> rows = new ArrayList<>();
        LongPairSet seen = new LongPairSet(comments.size());
        for (long[] c : comments) {
            int k = rnd.nextInt(3);   // 0~2개
            for (int i = 0; i < k; i++) {
                long m = memberIds.get(rnd.nextInt(memberIds.size()));
                if (m != c[2] && seen.add(c[0], m)) rows.add(new long[]{c[0], m, afterMinute(rnd, c[4])});
            }
        }
        batch("INSERT IGNORE INTO comment_likes (comment_id, member_id, created_at) VALUES (?, ?, ?)", rows,
                (ps, r) -> { ps.setLong(1, r[0]); ps.setLong(2, r[1]); ps.setTimestamp(3, minuteTs(r[2])); });
        log.info("[ScaleSeeder] comment_likes {}행 ({}ms)", rows.size(), System.currentTimeMillis() - t);
    }

    // ── 8. 감상 좋아요 ─────────────────────────────────────────────────
    private void seedReviewLikes(Random rnd, List<Long> memberIds, List<long[]> reviews) {
        long t = System.currentTimeMillis();
        Set<Long> hasLike = new HashSet<>(jdbc.queryForList("SELECT DISTINCT review_id FROM review_likes", Long.class));
        List<long[]> rows = new ArrayList<>();
        LongPairSet seen = new LongPairSet(reviews.size() * (props.getLikesPerReview() + 1));
        for (long[] r : reviews) {
            if (r[4] == 0 || hasLike.contains(r[0])) continue;
            int k = rnd.nextInt(props.getLikesPerReview() * 2 + 1);
            for (int i = 0; i < k; i++) {
                long m = memberIds.get(rnd.nextInt(memberIds.size()));
                if (m != r[1] && seen.add(r[0], m)) rows.add(new long[]{r[0], m, afterMinute(rnd, r[3])});
            }
        }
        batch("INSERT IGNORE INTO review_likes (review_id, member_id, created_at) VALUES (?, ?, ?)", rows,
                (ps, r) -> { ps.setLong(1, r[0]); ps.setLong(2, r[1]); ps.setTimestamp(3, minuteTs(r[2])); });
        log.info("[ScaleSeeder] review_likes {}행 ({}ms)", rows.size(), System.currentTimeMillis() - t);
    }

    // ── 9. 피드 + FOLLOWING_REVIEW 알림 ─────────────────────────────────
    /** 팔로위의 최근 공개 감상을 피드에 넣고, 같은 (팔로워, 감상) 쌍으로 FOLLOWING_REVIEW 알림도 만든다. */
    private void seedFeedsAndFollowingReviewNotifications(Random rnd, List<Long> memberIds, List<long[]> reviews) {
        long t = System.currentTimeMillis();
        Map<Long, List<long[]>> byAuthor = new HashMap<>();
        for (long[] r : reviews) if (r[4] == 1) byAuthor.computeIfAbsent(r[1], k -> new ArrayList<>()).add(r);
        for (List<long[]> l : byAuthor.values()) l.sort((a, b) -> Long.compare(b[3], a[3]));
        Set<Long> hasFeed = new HashSet<>(jdbc.queryForList("SELECT DISTINCT member_id FROM feeds", Long.class));
        List<long[]> rows = new ArrayList<>();
        for (long m : memberIds) {
            if (hasFeed.contains(m)) continue;
            List<Long> followees = jdbc.queryForList("SELECT followee_id FROM follows WHERE follower_id = ?", Long.class, m);
            List<long[]> cand = new ArrayList<>();
            for (long f : followees) cand.addAll(byAuthor.getOrDefault(f, List.of()));
            cand.sort((a, b) -> Long.compare(b[3], a[3]));
            for (long[] r : cand.subList(0, Math.min(props.getFeedsPerMember(), cand.size()))) rows.add(new long[]{m, r[0], r[3], r[1]});
        }
        batch("INSERT INTO feeds (member_id, review_id, created_at) VALUES (?, ?, ?)", rows,
                (ps, r) -> { ps.setLong(1, r[0]); ps.setLong(2, r[1]); ps.setTimestamp(3, minuteTs(r[2])); });
        // 피드 항목 중 일부(약 1/5)에 FOLLOWING_REVIEW 알림
        List<long[]> notis = new ArrayList<>();
        for (long[] r : rows) if (rnd.nextInt(5) == 0) notis.add(r);
        batch("INSERT INTO notifications (member_id, actor_member_id, type, review_id, review_like_id, comment_id, follow_id, message, is_read, is_deleted, created_at) VALUES (?, ?, 'FOLLOWING_REVIEW', ?, NULL, NULL, NULL, NULL, ?, 0, ?)",
                notis, (ps, r) -> { ps.setLong(1, r[0]); ps.setLong(2, r[3]); ps.setLong(3, r[1]); ps.setBoolean(4, rnd.nextInt(10) < 6); ps.setTimestamp(5, minuteTs(r[2])); });
        log.info("[ScaleSeeder] feeds {}행 + FOLLOWING_REVIEW 알림 {}행 ({}ms)", rows.size(), notis.size(), System.currentTimeMillis() - t);
    }

    // ── 10. 알림 (REVIEW_LIKE / COMMENT / FOLLOW) ───────────────────────
    /** 수신자별로 후보를 그때그때 조회해 메모리에 전부 올리지 않는다. review_like_id 는 앱도 쓰지 않으므로 NULL. */
    private void seedNotifications(Random rnd, List<Long> memberIds, List<long[]> reviews, List<long[]> comments) {
        long t = System.currentTimeMillis();
        Map<Long, List<long[]>> commentsByOwner = new HashMap<>();
        for (long[] c : comments) commentsByOwner.computeIfAbsent(c[3], k -> new ArrayList<>()).add(c);
        Set<Long> hasNoti = new HashSet<>(jdbc.queryForList("SELECT DISTINCT member_id FROM notifications WHERE type IN ('REVIEW_LIKE','COMMENT','FOLLOW')", Long.class));
        int per = props.getNotificationsPerMember();
        List<long[]> rows = new ArrayList<>();
        for (long m : memberIds) {
            if (hasNoti.contains(m)) continue;
            // {type, actor, review, comment, follow, eventMinute}
            List<long[]> cand = new ArrayList<>();
            jdbc.query("SELECT l.member_id, l.review_id, UNIX_TIMESTAMP(l.created_at) DIV 60 FROM review_likes l JOIN reviews r ON r.review_id = l.review_id WHERE r.member_id = ? LIMIT ?",
                    (RowCallbackHandler) rs -> cand.add(new long[]{0, rs.getLong(1), rs.getLong(2), 0, 0, rs.getLong(3)}), m, per * 2);
            for (long[] c : commentsByOwner.getOrDefault(m, List.of())) { if (cand.size() >= per * 4) break; cand.add(new long[]{1, c[2], c[1], c[0], 0, c[4]}); }
            jdbc.query("SELECT follow_id, follower_id, UNIX_TIMESTAMP(created_at) DIV 60 FROM follows WHERE followee_id = ? LIMIT ?",
                    (RowCallbackHandler) rs -> cand.add(new long[]{2, rs.getLong(2), 0, 0, rs.getLong(1), rs.getLong(3)}), m, per * 2);
            if (cand.isEmpty()) continue;
            Collections.shuffle(cand, rnd);
            for (long[] c : cand.subList(0, Math.min(per, cand.size()))) rows.add(new long[]{m, c[0], c[1], c[2], c[3], c[4], c[5]});
        }
        String[] types = {"REVIEW_LIKE", "COMMENT", "FOLLOW"};
        batch("INSERT INTO notifications (member_id, actor_member_id, type, review_id, review_like_id, comment_id, follow_id, message, is_read, is_deleted, created_at) VALUES (?, ?, ?, ?, NULL, ?, ?, NULL, ?, 0, ?)",
                rows, (ps, r) -> {
                    int type = (int) r[1];
                    ps.setLong(1, r[0]); ps.setLong(2, r[2]); ps.setString(3, types[type]);
                    if (type == 2) ps.setNull(4, Types.BIGINT); else ps.setLong(4, r[3]);
                    if (type == 1) ps.setLong(5, r[4]); else ps.setNull(5, Types.BIGINT);
                    if (type == 2) ps.setLong(6, r[5]); else ps.setNull(6, Types.BIGINT);
                    ps.setBoolean(7, rnd.nextInt(10) < 6);
                    ps.setTimestamp(8, minuteTs(r[6]));   // 알림은 원인 이벤트와 같은 시각
                });
        log.info("[ScaleSeeder] notifications {}행 ({}ms)", rows.size(), System.currentTimeMillis() - t);
    }

    // ── 11. 카운터 정합 (PK 범위로 나눠 실행) ──────────────────────────
    private void fixCounters(List<Long> memberIds, List<long[]> reviews, List<long[]> comments) {
        long t = System.currentTimeMillis();
        chunkedUpdate("""
                UPDATE members m SET
                  follower_count  = (SELECT COUNT(*) FROM follows f WHERE f.followee_id = m.member_id),
                  following_count = (SELECT COUNT(*) FROM follows f WHERE f.follower_id = m.member_id),
                  review_count    = (SELECT COUNT(*) FROM reviews r WHERE r.member_id = m.member_id AND r.review_status = 'PUBLISHED' AND r.is_deleted = 0)
                WHERE m.member_id BETWEEN ? AND ?
                """, memberIds.isEmpty() ? 0 : memberIds.get(0), memberIds.isEmpty() ? -1 : memberIds.get(memberIds.size() - 1));
        long rLo = reviews.stream().mapToLong(r -> r[0]).min().orElse(0), rHi = reviews.stream().mapToLong(r -> r[0]).max().orElse(-1);
        chunkedUpdate("""
                UPDATE reviews r SET
                  like_count    = (SELECT COUNT(*) FROM review_likes l WHERE l.review_id = r.review_id),
                  comment_count = (SELECT COUNT(*) FROM comments c WHERE c.review_id = r.review_id AND c.is_deleted = 0)
                WHERE r.review_id BETWEEN ? AND ?
                """, rLo, rHi);
        long cLo = comments.stream().mapToLong(c -> c[0]).min().orElse(0), cHi = comments.stream().mapToLong(c -> c[0]).max().orElse(-1);
        chunkedUpdate("UPDATE comments c SET like_count = (SELECT COUNT(*) FROM comment_likes cl WHERE cl.comment_id = c.comment_id) WHERE c.comment_id BETWEEN ? AND ?", cLo, cHi);
        log.info("[ScaleSeeder] 카운터 재계산 ({}ms)", System.currentTimeMillis() - t);
    }

    private void chunkedUpdate(String sql, long lo, long hi) {
        for (long from = lo; from <= hi; from += COUNTER_CHUNK) jdbc.update(sql, from, Math.min(hi, from + COUNTER_CHUNK - 1));
    }

    // ── helpers ────────────────────────────────────────────────────────
    private interface RowBinder<T> { void bind(PreparedStatement ps, T row) throws SQLException; }

    private <T> void batch(String sql, List<T> rows, RowBinder<T> binder) {
        for (int i = 0; i < rows.size(); i += props.getBatchSize()) {
            List<T> chunk = rows.subList(i, Math.min(rows.size(), i + props.getBatchSize()));
            jdbc.batchUpdate(sql, chunk, chunk.size(), binder::bind);
        }
    }

    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }

    /** 부모 이벤트(epoch 분) 이후 0~30일 사이의 시각(epoch 분). 자식이 부모보다 앞서지 않게 한다. */
    private static long afterMinute(Random rnd, long parentMinute) { return parentMinute + rnd.nextInt(MINUTES_30D); }

    private static Timestamp minuteTs(long epochMinute) { return new Timestamp(epochMinute * 60_000L); }

    /** 인기 편향 인덱스: u³ 분포라 앞쪽(대출 상위 10%)이 약 46%, 상위 50% 가 약 79% 를 차지한다. */
    private static int popularityBiased(Random rnd, int n) {
        double u = rnd.nextDouble();
        return Math.min(n - 1, (int) (u * u * u * n));
    }

    private static int pick(Random rnd, int[] weights) {
        int sum = Arrays.stream(weights).sum(), roll = rnd.nextInt(sum), acc = 0;
        for (int i = 0; i < weights.length; i++) { acc += weights[i]; if (roll < acc) return i; }
        return weights.length - 1;
    }

    private static String cut(String s, int max) { return s == null ? null : (s.length() <= max ? s : s.substring(0, max)); }
    private static String blankTo(String s, String d) { return s == null || s.isBlank() ? d : s; }

    /** RFC 4180 최소 구현: 따옴표 안의 콤마·줄바꿈·"" 이스케이프를 처리한다. */
    static List<Map<String, String>> readCsv(Path path) throws IOException {
        List<Map<String, String>> rows = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            List<String> header = null;
            StringBuilder field = new StringBuilder();
            List<String> record = new ArrayList<>();
            boolean inQuotes = false;
            int ch;
            while ((ch = br.read()) != -1) {
                char c = (char) ch;
                if (inQuotes) {
                    if (c == '"') {
                        br.mark(1);
                        int nx = br.read();
                        if (nx == '"') field.append('"'); else { inQuotes = false; if (nx != -1) br.reset(); }
                    } else field.append(c);
                } else if (c == '"') inQuotes = true;
                else if (c == ',') { record.add(field.toString()); field.setLength(0); }
                else if (c == '\n' || c == '\r') {
                    if (c == '\r') { br.mark(1); if (br.read() != '\n') br.reset(); }
                    record.add(field.toString()); field.setLength(0);
                    if (header == null) header = new ArrayList<>(record);
                    else if (record.size() > 1 || !record.get(0).isEmpty()) {
                        Map<String, String> m = new HashMap<>();
                        for (int i = 0; i < header.size() && i < record.size(); i++) m.put(header.get(i), record.get(i));
                        rows.add(m);
                    }
                    record.clear();
                } else field.append(c);
            }
            if (!field.isEmpty() || !record.isEmpty()) {
                record.add(field.toString());
                if (header != null) {
                    Map<String, String> m = new HashMap<>();
                    for (int i = 0; i < header.size() && i < record.size(); i++) m.put(header.get(i), record.get(i));
                    rows.add(m);
                }
            }
        }
        return rows;
    }

    /** app.seed.exit-after=true 이면 시딩 직후 종료 코드 0 으로 애플리케이션을 내린다. */
    private void exitIfRequested() {
        if (!props.isExitAfter()) return;
        log.info("[ScaleSeeder] app.seed.exit-after=true → 애플리케이션 종료");
        int code = org.springframework.boot.SpringApplication.exit(ctx, () -> 0);
        System.exit(code);
    }
}
