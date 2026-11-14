-- V4: reviews.view_count 추가 (조회수 행 락 경합 해소 작업).
-- 감상글 상세 조회수를 Redis 에 누적하고 분당 배치로 이 컬럼에 반영한다(ViewCountService).
-- 이 작업을 하던 브랜치는 Flyway 도입(#70) 이전에 분기돼 ddl-auto=update 로 컬럼을 만들고 있었다.
-- 그 환경에서 복원한 DB 에는 컬럼이 이미 있으므로 V2 와 같은 information_schema 검사로 멱등하게 처리한다.

-- 컬럼이 이미 있는 DB 에서는 프로시저 본문이 no-op 이다.
-- 이 스크립트를 실행하는 계정에는 스키마에 대한 CREATE ROUTINE 권한이 필요하다.
DROP PROCEDURE IF EXISTS shelfeed_v4_add_review_view_count;

CREATE PROCEDURE shelfeed_v4_add_review_view_count()
BEGIN
    DECLARE has_col INT DEFAULT 0;

    SELECT COUNT(*) INTO has_col FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reviews' AND COLUMN_NAME = 'view_count';

    IF has_col = 0 THEN
        -- like_count / comment_count 와 같은 정의(int NOT NULL). 기존 행은 0 으로 채운다.
        ALTER TABLE reviews ADD COLUMN view_count INT NOT NULL DEFAULT 0;
    END IF;
END;

CALL shelfeed_v4_add_review_view_count();

DROP PROCEDURE shelfeed_v4_add_review_view_count;
