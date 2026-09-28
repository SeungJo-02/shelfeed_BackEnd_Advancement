-- V1 baseline: PR #68(YES24 전환) 시점의 엔티티에서 Hibernate(ddl-auto=create)가 만든 스키마를
-- MySQL 8.0 컨테이너에서 mysqldump --no-data 로 뽑아 정리한 것이다.
-- 이후 스키마 변경은 이 파일을 고치지 말고 V{n}__*.sql 을 추가한다.
-- member_user_id_seq 의 초기 행은 앱이 기동 시 INSERT IGNORE 로 스스로 만들므로(MemberUserIdSequenceRepository.createIfAbsent) 여기서 넣지 않는다.
-- mysqldump 는 테이블을 이름순으로 내보내므로 FK 대상보다 참조 테이블이 먼저 나올 수 있다. 생성 동안만 FK 검사를 끈다.
-- MySQL DDL 은 트랜잭션이 아니다. 중간에 실패하면 일부 테이블이 남으므로 정리 후 `flyway repair` 로 히스토리를 고쳐야 한다.

SET FOREIGN_KEY_CHECKS = 0;

CREATE TABLE `blocks` (
  `block_id` bigint NOT NULL AUTO_INCREMENT,
  `blocked_id` bigint NOT NULL,
  `blocker_id` bigint NOT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`block_id`),
  UNIQUE KEY `UKo6p3yjxo8qvcqrxt673wxsx63` (`blocker_id`,`blocked_id`),
  KEY `FKey30pfd7c4u1qk8qsp1ehr5yv` (`blocked_id`),
  CONSTRAINT `FKe7icvrf23na068ljwwplll2ej` FOREIGN KEY (`blocker_id`) REFERENCES `members` (`member_id`),
  CONSTRAINT `FKey30pfd7c4u1qk8qsp1ehr5yv` FOREIGN KEY (`blocked_id`) REFERENCES `members` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `books` (
  `published_date` date DEFAULT NULL,
  `total_pages` int DEFAULT NULL,
  `book_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `isbn13` varchar(13) NOT NULL,
  `author` varchar(50) NOT NULL,
  `external_item_id` varchar(50) DEFAULT NULL,
  `category` varchar(100) DEFAULT NULL,
  `genre` varchar(100) DEFAULT NULL,
  `publisher` varchar(200) DEFAULT NULL,
  `cover_image_url` varchar(500) DEFAULT NULL,
  `title` varchar(500) NOT NULL,
  `description` text,
  PRIMARY KEY (`book_id`),
  UNIQUE KEY `UKsesykpxp69k5u88p4654qsdo0` (`isbn13`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `comment_likes` (
  `comment_id` bigint NOT NULL,
  `comment_like_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `member_id` bigint NOT NULL,
  PRIMARY KEY (`comment_like_id`),
  UNIQUE KEY `UKsak8n9q3iw3we5yjkuvqm8o5d` (`member_id`,`comment_id`),
  KEY `FK3wa5u7bs1p1o9hmavtgdgk1go` (`comment_id`),
  CONSTRAINT `FK3wa5u7bs1p1o9hmavtgdgk1go` FOREIGN KEY (`comment_id`) REFERENCES `comments` (`comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `comments` (
  `is_deleted` bit(1) NOT NULL,
  `like_count` int NOT NULL,
  `comment_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `member_id` bigint NOT NULL,
  `parent_comment_id` bigint DEFAULT NULL,
  `review_id` bigint NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `content` varchar(1000) NOT NULL,
  PRIMARY KEY (`comment_id`),
  KEY `FK7h839m3lkvhbyv3bcdv7sm4fj` (`parent_comment_id`),
  KEY `FKdpo60i7auk5cudv7kkny8jrqb` (`review_id`),
  CONSTRAINT `FK7h839m3lkvhbyv3bcdv7sm4fj` FOREIGN KEY (`parent_comment_id`) REFERENCES `comments` (`comment_id`),
  CONSTRAINT `FKdpo60i7auk5cudv7kkny8jrqb` FOREIGN KEY (`review_id`) REFERENCES `reviews` (`review_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `feeds` (
  `created_at` datetime(6) NOT NULL,
  `feed_id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint NOT NULL,
  `review_id` bigint NOT NULL,
  PRIMARY KEY (`feed_id`),
  KEY `FK4itbxlri5qb3cymon3tlqhb3h` (`member_id`),
  KEY `FKhqljc1rpptnbeg7tnfxnmg9pw` (`review_id`),
  CONSTRAINT `FK4itbxlri5qb3cymon3tlqhb3h` FOREIGN KEY (`member_id`) REFERENCES `members` (`member_id`),
  CONSTRAINT `FKhqljc1rpptnbeg7tnfxnmg9pw` FOREIGN KEY (`review_id`) REFERENCES `reviews` (`review_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `follows` (
  `created_at` datetime(6) NOT NULL,
  `follow_id` bigint NOT NULL AUTO_INCREMENT,
  `followee_id` bigint NOT NULL,
  `follower_id` bigint NOT NULL,
  PRIMARY KEY (`follow_id`),
  UNIQUE KEY `UK53c7l7tclgps8jhvuioqbti7n` (`follower_id`,`followee_id`),
  KEY `FKrtsspv12vebpg8i9n1r1rv55s` (`followee_id`),
  CONSTRAINT `FKdr0b6ghbydatcju1ngkjr05ek` FOREIGN KEY (`follower_id`) REFERENCES `members` (`member_id`),
  CONSTRAINT `FKrtsspv12vebpg8i9n1r1rv55s` FOREIGN KEY (`followee_id`) REFERENCES `members` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `genres` (
  `genre_id` bigint NOT NULL AUTO_INCREMENT,
  `genre_name` varchar(50) NOT NULL,
  `category_pattern` varchar(100) DEFAULT NULL,
  PRIMARY KEY (`genre_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `library_books` (
  `finished_at` date DEFAULT NULL,
  `started_at` date DEFAULT NULL,
  `book_id` bigint NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `library_book_id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `status` enum('FINISHED','READING','STOPPED','WANT_TO_READ') NOT NULL,
  PRIMARY KEY (`library_book_id`),
  UNIQUE KEY `UK5fqw0sqa4hr4qxvoogutrh50y` (`member_id`,`book_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `member_genres` (
  `genre_id` bigint NOT NULL,
  `member_genre_id` bigint NOT NULL AUTO_INCREMENT,
  `member_id` bigint NOT NULL,
  PRIMARY KEY (`member_genre_id`),
  UNIQUE KEY `UK3jxoifqsdbnuae0q0vpaikk0c` (`member_id`,`genre_id`),
  KEY `FK4m0q65hcjahp6ins93aiwq9ob` (`genre_id`),
  CONSTRAINT `FK4m0q65hcjahp6ins93aiwq9ob` FOREIGN KEY (`genre_id`) REFERENCES `genres` (`genre_id`),
  CONSTRAINT `FKnu4090mqf8um4lybkcqkatkhs` FOREIGN KEY (`member_id`) REFERENCES `members` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `member_user_id_seq` (
  `id` int NOT NULL,
  `next_val` bigint NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `members` (
  `email_verified` bit(1) NOT NULL,
  `follower_count` int NOT NULL,
  `following_count` int NOT NULL,
  `onboarding_completed` bit(1) NOT NULL,
  `review_count` int NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `last_login_at` datetime(6) DEFAULT NULL,
  `member_id` bigint NOT NULL AUTO_INCREMENT,
  `member_user_id` bigint NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `withdrawn_at` datetime(6) DEFAULT NULL,
  `nickname` varchar(50) NOT NULL,
  `bio` varchar(300) DEFAULT NULL,
  `profile_image_url` varchar(500) DEFAULT NULL,
  `email` varchar(255) NOT NULL,
  `notification_preferences` json NOT NULL,
  `password` varchar(255) DEFAULT NULL,
  `library_visibility` enum('PRIVATE','PUBLIC') NOT NULL,
  `role` enum('ADMIN','USER') NOT NULL,
  `status` enum('ACTIVE','SUSPENDED','WITHDRAWN') NOT NULL,
  PRIMARY KEY (`member_id`),
  UNIQUE KEY `UKrrw2faueylasxb59cvhmm1srl` (`member_user_id`),
  UNIQUE KEY `UK9d30a9u1qpg8eou0otgkwrp5d` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `notifications` (
  `is_deleted` bit(1) NOT NULL,
  `is_read` bit(1) NOT NULL,
  `actor_member_id` bigint DEFAULT NULL,
  `comment_id` bigint DEFAULT NULL,
  `comment_like_id` bigint DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `follow_id` bigint DEFAULT NULL,
  `member_id` bigint NOT NULL,
  `notification_id` bigint NOT NULL AUTO_INCREMENT,
  `review_id` bigint DEFAULT NULL,
  `review_like_id` bigint DEFAULT NULL,
  `message` varchar(500) DEFAULT NULL,
  `type` enum('COMMENT','COMMENT_LIKE','FOLLOW','FOLLOWING_REVIEW','REVIEW_LIKE','SYSTEM') NOT NULL,
  PRIMARY KEY (`notification_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `reports` (
  `comment_id` bigint DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `member_id` bigint NOT NULL,
  `report_id` bigint NOT NULL AUTO_INCREMENT,
  `resolved_at` datetime(6) DEFAULT NULL,
  `review_id` bigint DEFAULT NULL,
  `detail` varchar(200) DEFAULT NULL,
  `reason` enum('COPYRIGHT','INAPPROPRIATE','OTHER','SPAM','SPOILER') NOT NULL,
  `status` enum('PENDING','REJECTED','RESOLVED') NOT NULL,
  PRIMARY KEY (`report_id`),
  UNIQUE KEY `UKh0ldneps79v6197dkkydauqfn` (`member_id`,`review_id`),
  UNIQUE KEY `UK22d2cewo5eohygwsqabm3spj1` (`member_id`,`comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `review_drafts` (
  `is_spoiler` bit(1) DEFAULT NULL,
  `rating` tinyint DEFAULT NULL,
  `read_pages` int DEFAULT NULL,
  `book_id` bigint DEFAULT NULL,
  `drafts_id` bigint NOT NULL AUTO_INCREMENT,
  `library_book_id` bigint DEFAULT NULL,
  `member_id` bigint NOT NULL,
  `content` text,
  `review_visibility` enum('FOLLOWER','PRIVATE','PUBLIC') DEFAULT NULL,
  PRIMARY KEY (`drafts_id`),
  KEY `FKngov3ee6mtp8aw5bdfh2obf0k` (`library_book_id`),
  CONSTRAINT `FKngov3ee6mtp8aw5bdfh2obf0k` FOREIGN KEY (`library_book_id`) REFERENCES `library_books` (`library_book_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `review_likes` (
  `created_at` datetime(6) NOT NULL,
  `member_id` bigint NOT NULL,
  `review_id` bigint NOT NULL,
  `review_like_id` bigint NOT NULL AUTO_INCREMENT,
  PRIMARY KEY (`review_like_id`),
  UNIQUE KEY `UKqelihi4g145hvxmq1tl6ou5oh` (`member_id`,`review_id`),
  KEY `FKm2uonfg8ky6jwtu6iugkilox8` (`review_id`),
  CONSTRAINT `FKm2uonfg8ky6jwtu6iugkilox8` FOREIGN KEY (`review_id`) REFERENCES `reviews` (`review_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `review_tags` (
  `review_id` bigint NOT NULL,
  `review_tag_id` bigint NOT NULL AUTO_INCREMENT,
  `tag_id` bigint NOT NULL,
  PRIMARY KEY (`review_tag_id`),
  KEY `FKg1dakl1b69tatg7gfwhs11nml` (`review_id`),
  KEY `FKmm7uy7rpfyb91qxb0kwg19hpa` (`tag_id`),
  CONSTRAINT `FKg1dakl1b69tatg7gfwhs11nml` FOREIGN KEY (`review_id`) REFERENCES `reviews` (`review_id`),
  CONSTRAINT `FKmm7uy7rpfyb91qxb0kwg19hpa` FOREIGN KEY (`tag_id`) REFERENCES `tags` (`tag_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `reviews` (
  `comment_count` int NOT NULL,
  `is_deleted` bit(1) NOT NULL,
  `is_spoiler` bit(1) NOT NULL,
  `like_count` int NOT NULL,
  `rating` tinyint NOT NULL,
  `read_pages` int DEFAULT NULL,
  `book_id` bigint NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `library_book_id` bigint DEFAULT NULL,
  `member_id` bigint NOT NULL,
  `review_id` bigint NOT NULL AUTO_INCREMENT,
  `updated_at` datetime(6) NOT NULL,
  `content` text,
  `quote` text,
  `review_status` enum('DRAFT','PUBLISHED') NOT NULL,
  `review_visibility` enum('FOLLOWER','PRIVATE','PUBLIC') NOT NULL,
  PRIMARY KEY (`review_id`),
  KEY `FK6a9k6xvev80se5rreqvuqr7f9` (`book_id`),
  KEY `FKj6ryyyefxnolqd8misyeuwxdv` (`library_book_id`),
  KEY `FK6wsc8rr8tb1fc782foh3mjc8q` (`member_id`),
  CONSTRAINT `FK6a9k6xvev80se5rreqvuqr7f9` FOREIGN KEY (`book_id`) REFERENCES `books` (`book_id`),
  CONSTRAINT `FK6wsc8rr8tb1fc782foh3mjc8q` FOREIGN KEY (`member_id`) REFERENCES `members` (`member_id`),
  CONSTRAINT `FKj6ryyyefxnolqd8misyeuwxdv` FOREIGN KEY (`library_book_id`) REFERENCES `library_books` (`library_book_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `search_histories` (
  `created_at` datetime(6) NOT NULL,
  `member_id` bigint NOT NULL,
  `search_history_id` bigint NOT NULL AUTO_INCREMENT,
  `keyword` varchar(200) NOT NULL,
  PRIMARY KEY (`search_history_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `social_accounts` (
  `created_at` datetime(6) NOT NULL,
  `member_id` bigint NOT NULL,
  `social_account_id` bigint NOT NULL AUTO_INCREMENT,
  `provider` varchar(20) NOT NULL,
  `provider_id` varchar(255) NOT NULL,
  PRIMARY KEY (`social_account_id`),
  UNIQUE KEY `UKq7w5kmcebma8jj941snwctfju` (`provider`,`provider_id`),
  KEY `FK6nbaebfdf5rr4r5g5819claex` (`member_id`),
  CONSTRAINT `FK6nbaebfdf5rr4r5g5819claex` FOREIGN KEY (`member_id`) REFERENCES `members` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `tags` (
  `tag_id` bigint NOT NULL AUTO_INCREMENT,
  `tag_name` varchar(100) DEFAULT NULL,
  PRIMARY KEY (`tag_id`),
  UNIQUE KEY `UK2c6s9hekidseaj5vbgb3pgy3k` (`tag_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

SET FOREIGN_KEY_CHECKS = 1;
