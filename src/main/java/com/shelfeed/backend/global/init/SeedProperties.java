package com.shelfeed.backend.global.init;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@link ScaleSeeder} 파라미터. {@code app.seed.*} 로 바인딩되며 CLI 인자(--app.seed.members=200)로 덮어쓸 수 있다.
 * 기본값은 이슈 #71 의 목표 규모다.
 */
@Getter
@Setter
@ToString
@ConfigurationProperties(prefix = "app.seed")
public class SeedProperties {
    /** 정보나루 수집 CSV. 없으면 seed/data/books.sample.csv 로 대체한다. */
    private String booksCsv = "seed/data/books.csv";
    private int members = 5_000;
    private int reviews = 200_000;
    /** 허브가 아닌 회원의 평균 팔로잉 수. */
    private int followsPerMember = 30;
    /** 상위 hubRatio 비율의 회원이 허브가 되어 hubFollowers 명의 팔로워를 받는다. */
    private double hubRatio = 0.01;
    private int hubFollowers = 3_000;
    private int libraryPerMember = 60;
    private int commentsPerReview = 2;
    private int likesPerReview = 5;
    private int feedsPerMember = 50;
    private int notificationsPerMember = 20;
    private int batchSize = 2_000;
    /** 결정적 생성을 위한 난수 시드. */
    private long seed = 42L;
    /** true 면 시딩이 끝난 뒤 애플리케이션을 종료한다 (스크립트·CI 용). 기본 false: 시딩 후 서버로 계속 뜬다. */
    private boolean exitAfter = false;
}
