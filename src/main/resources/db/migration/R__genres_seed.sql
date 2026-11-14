-- 장르 시드. 반복 마이그레이션(R__)이라 내용(체크섬)이 바뀔 때마다 다시 실행된다.
-- ON DUPLICATE KEY UPDATE 로 멱등하다. 기존 data.sql 을 옮긴 것.
-- VALUES(col) 형식은 MySQL 8.0.20 부터 deprecated(실행마다 경고)라 8.0.19+ 의 행 별칭(AS new) 형식을 쓴다.

INSERT INTO genres (genre_id, genre_name, category_pattern) VALUES
(1,  '소설',           '소설/시/희곡'),
(2,  '장르소설',       '판타지/환상문학|과학소설|호러.공포소설|추리/미스터리소설'),
(3,  '에세이',         '에세이'),
(4,  '인문학',         '인문'),
(5,  '역사',           '역사'),
(6,  '과학',           '[>-](자연)?과학'),
(7,  '사회과학',       '사회과학|사회 정치'),
(8,  '경제경영',       '경제 ?경영'),
(9,  '자기계발',       '자기계발'),
(10, '예술/대중문화',  '예술'),
(11, '여행',           '여행'),
(12, '건강/취미',      '건강[/ ]취미'),
(13, '요리/살림',      '요리/살림|가정 살림'),
(14, '종교/역학',      '종교'),
(15, '만화/라이트노벨','만화/라이트노벨') AS new
ON DUPLICATE KEY UPDATE genre_name = new.genre_name, category_pattern = new.category_pattern;
