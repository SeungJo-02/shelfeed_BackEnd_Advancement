-- V3: baseline-on-migrate 로 V1 을 건너뛴 기존 DB 중 PR #57(회원 번호 DB 시퀀스) 이전 덤프에는
-- member_user_id_seq 가 없어 Hibernate validate 가 기동을 막는다. V1 과 같은 정의로 없을 때만 만든다.
-- 초기 행은 앱이 기동 시 INSERT IGNORE 로 스스로 넣는다(MemberUserIdSequenceRepository.createIfAbsent).
CREATE TABLE IF NOT EXISTS `member_user_id_seq` (
  `id` int NOT NULL,
  `next_val` bigint NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
