package com.shelfeed.backend.global.init;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScaleSeederGuardTest {

    @Test
    @DisplayName("prod 프로파일이면 중단한다")
    void rejectsProdProfile() {
        assertThatThrownBy(() -> ScaleSeeder.guardTarget(new String[]{"prod", "scale-seed"}, "jdbc:mysql://localhost:3308/shelfeed"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("prod");
    }

    @Test
    @DisplayName("로컬이 아닌 DB 호스트면 중단한다")
    void rejectsRemoteHost() {
        assertThatThrownBy(() -> ScaleSeeder.guardTarget(new String[]{"scale-seed"}, "jdbc:mysql://shelfeed-db.cpycawsqc9yi.ap-northeast-2.rds.amazonaws.com:3306/shelfeed?useSSL=false"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("rds.amazonaws.com");
    }

    @Test
    @DisplayName("localhost·127.0.0.1·mysql·mysql-perf 는 허용한다")
    void allowsLocalHosts() {
        for (String h : new String[]{"localhost", "127.0.0.1", "mysql", "mysql-perf"}) {
            ScaleSeeder.guardTarget(new String[]{"mock-catalog", "scale-seed"}, "jdbc:mysql://" + h + ":3306/shelfeed?useSSL=false&rewriteBatchedStatements=true");
        }
        assertThat(ScaleSeeder.jdbcHost("jdbc:mysql://MySQL-Perf:3306/x")).isEqualTo("mysql-perf");
        assertThat(ScaleSeeder.jdbcHost("garbage")).isEmpty();
    }
}
