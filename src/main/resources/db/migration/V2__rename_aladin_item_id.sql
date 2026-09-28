-- V2: books.aladin_item_id → external_item_id 보정 (#68).
-- 새 DB(V1 적용)에는 이미 external_item_id 만 있어 no-op.
-- 옛 덤프에서 복원한 DB(aladin_item_id 만 있음)는 RENAME.
-- ddl-auto=update 가 먼저 돌아 두 컬럼이 공존하는 DB는 값을 옮기고 옛 컬럼을 DROP.
-- information_schema 검사로 세 경우를 모두 멱등하게 처리한다.

-- 새 DB 에는 프로시저가 없어 MySQL note 1305 가 Flyway WARN 으로 찍힌다. 정상이다.
-- 이 스크립트를 실행하는 계정에는 스키마에 대한 CREATE ROUTINE 권한이 필요하다.
DROP PROCEDURE IF EXISTS shelfeed_v2_rename_aladin_item_id;

CREATE PROCEDURE shelfeed_v2_rename_aladin_item_id()
BEGIN
    DECLARE has_old INT DEFAULT 0;
    DECLARE has_new INT DEFAULT 0;

    SELECT COUNT(*) INTO has_old FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'books' AND COLUMN_NAME = 'aladin_item_id';
    SELECT COUNT(*) INTO has_new FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'books' AND COLUMN_NAME = 'external_item_id';

    IF has_old = 1 AND has_new = 0 THEN
        ALTER TABLE books RENAME COLUMN aladin_item_id TO external_item_id;
    ELSEIF has_old = 1 AND has_new = 1 THEN
        UPDATE books SET external_item_id = aladin_item_id WHERE external_item_id IS NULL;
        ALTER TABLE books DROP COLUMN aladin_item_id;
    END IF;
END;

CALL shelfeed_v2_rename_aladin_item_id();

DROP PROCEDURE shelfeed_v2_rename_aladin_item_id;
