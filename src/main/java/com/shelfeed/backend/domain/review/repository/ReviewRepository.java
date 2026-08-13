package com.shelfeed.backend.domain.review.repository;

import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.review.entity.Review;
import com.shelfeed.backend.domain.review.enums.ReviewStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    @Query("SELECT r FROM Review r JOIN FETCH r.member WHERE r.isDeleted = false AND r.reviewStatus = 'PUBLISHED'")
    List<Review> findAllPublishedWithMember();

    boolean existsByMemberAndBook_BookIdAndIsDeletedFalse(Member member, Long bookId);//중복 감상 체크
    boolean existsByMember_MemberUserIdAndBook_BookIdAndIsDeletedFalse(Long memberUserId, Long bookId);//서재 도서 삭제 시 감상 존재 여부 체크
    Optional<Review> findByMemberAndBook_BookIdAndIsDeletedFalse(Member member, Long bookId);
    //내 감상 목록 커서 페이지 네이션
    @Query("SELECT r FROM Review r JOIN FETCH r.member JOIN FETCH r.book " +
            "WHERE r.member = :member AND r.isDeleted = false " +
            "AND (:status IS NULL OR r.reviewStatus = :status) " + // 프론트엔드가 특정 상태를 안 보내면 앞 조건이 참이 되어 전체를 다 보여주고, 특정 값을 보내면 딱 그 상태의 리뷰만 걸러내는 아주 똑똑한 선택적 검색 조건
            "AND (:cursor IS NULL OR r.reviewId < :cursor) " +// 무한 스크롤 커서
            "ORDER BY r.reviewId DESC")// 가장 최근에 쓴 최신 글 순서대로
    List<Review> findMyReviews(@Param("member") Member member,
                               @Param("status") ReviewStatus status,
                               @Param("cursor") Long cursor,
                               Pageable pageable);

    @Query("SELECT r FROM Review r JOIN FETCH r.member JOIN FETCH r.book " +
            "WHERE r.member = :member AND r.isDeleted = false " +
            "AND r.reviewVisibility = 'PUBLIC' AND r.reviewStatus = 'PUBLISHED' " +
            "AND (:cursor IS NULL OR r.reviewId < :cursor) " +
            "ORDER BY r.reviewId DESC")
    List<Review> findUserReviews(@Param("member") Member member, @Param("cursor") Long cursor,
                                 Pageable pageable);
    Optional<Review> findByReviewIdAndIsDeletedFalse(Long reviewId);

    //최신순(커서 기반)
    @Query("""
        SELECT r from Review r WHERE r.book.bookId = :bookId
        AND r.isDeleted = false
        AND r.reviewVisibility = 'PUBLIC'
        AND r.reviewStatus = 'PUBLISHED'
        AND(:cursor IS NULL OR r.reviewId < :cursor)
        ORDER BY r.reviewId DESC
""")
    List<Review> findBookReviewsLatest(@Param("bookId") Long bookId,
                                       @Param("cursor") Long cursor,
                                       Pageable pageable);

    //인기순(커서 기반) — (likeCount DESC, reviewId DESC) 복합 커서
    @Query("""
        SELECT r from Review r WHERE r.book.bookId = :bookId
        AND r.isDeleted = false
        AND r.reviewVisibility = 'PUBLIC'
        AND r.reviewStatus = 'PUBLISHED'
        AND (:cursorLike IS NULL
             OR r.likeCount < :cursorLike
             OR (r.likeCount = :cursorLike AND r.reviewId < :cursorId))
        ORDER BY r.likeCount DESC, r.reviewId DESC
""")
    List<Review> findBookReviewsPopular(@Param("bookId") Long bookId,
                                        @Param("cursorLike") Integer cursorLike,
                                        @Param("cursorId") Long cursorId,
                                        Pageable pageable);



    //평점이 높은 순(커서 기반) — (rating DESC, reviewId DESC) 복합 커서
    @Query("""
        SELECT r FROM Review r WHERE r.book.bookId = :bookId
        AND r.isDeleted = false
        AND r.reviewVisibility = 'PUBLIC'
        AND r.reviewStatus = 'PUBLISHED'
        AND (:cursorRating IS NULL
             OR r.rating < :cursorRating
             OR (r.rating = :cursorRating AND r.reviewId < :cursorId))
        ORDER BY r.rating DESC, r.reviewId DESC
""")
    List<Review> findBookReviewsRatingHigh(@Param("bookId") Long bookId,
                                           @Param("cursorRating") Integer cursorRating,
                                           @Param("cursorId") Long cursorId,
                                           Pageable pageable);

    //평점이 낮은 순(커서 기반) — (rating ASC, reviewId DESC) 복합 커서
    @Query("""
        SELECT r FROM Review r WHERE r.book.bookId = :bookId
        AND r.isDeleted = false
        AND r.reviewVisibility = 'PUBLIC'
        AND r.reviewStatus = 'PUBLISHED'
        AND (:cursorRating IS NULL
             OR r.rating > :cursorRating
             OR (r.rating = :cursorRating AND r.reviewId < :cursorId))
        ORDER BY r.rating ASC, r.reviewId DESC
""")
    List<Review> findBookReviewsRatingLow(@Param("bookId") Long bookId,
                                          @Param("cursorRating") Integer cursorRating,
                                          @Param("cursorId") Long cursorId,
                                          Pageable pageable);

    // ── 통합 피드 추천 후보 쿼리 ──────────────────────────────────────────
    //
    // 추천 로직은 "어떤 감상을 후보로 넣을지"만 결정하고, 노출 순서는 통합 피드가
    // (createdAt, reviewId) DESC 커서로 일괄 결정한다. 그래서 정렬·커서 기준이
    // 좋아요 수가 아니라 작성 시각이다. 세 쿼리 모두 같은 커서 규약을 지켜야
    // FeedService에서 병합해도 페이지 경계가 어긋나지 않는다.

    // 장르 기반 추천 후보 (Content-based)
    @Query("""
        SELECT r FROM Review r JOIN FETCH r.member JOIN FETCH r.book b
        WHERE b.genre IN :genres
        AND r.isDeleted = false
        AND r.reviewVisibility = 'PUBLIC'
        AND r.reviewStatus = 'PUBLISHED'
        AND r.member <> :me
        AND (:cursorCreatedAt IS NULL
             OR r.createdAt < :cursorCreatedAt
             OR (r.createdAt = :cursorCreatedAt AND r.reviewId < :cursorId))
        ORDER BY r.createdAt DESC, r.reviewId DESC
    """)
    List<Review> findRecommendedByGenres(@Param("genres") List<String> genres,
                                         @Param("me") Member me,
                                         @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
                                         @Param("cursorId") Long cursorId,
                                         Pageable pageable);

    // 팔로우 유저 서재 기반 추천 후보 (Social-based)
    @Query("""
        SELECT r FROM Review r JOIN FETCH r.member JOIN FETCH r.book b
        WHERE b.bookId IN (
            SELECT lb.bookId FROM LibraryBook lb
            WHERE lb.memberId IN (
                SELECT f.followee.memberId FROM Follow f WHERE f.follower = :me
            )
            AND lb.status = 'FINISHED'
        )
        AND r.isDeleted = false
        AND r.reviewVisibility = 'PUBLIC'
        AND r.reviewStatus = 'PUBLISHED'
        AND r.member <> :me
        AND (:cursorCreatedAt IS NULL
             OR r.createdAt < :cursorCreatedAt
             OR (r.createdAt = :cursorCreatedAt AND r.reviewId < :cursorId))
        ORDER BY r.createdAt DESC, r.reviewId DESC
    """)
    List<Review> findRecommendedByFolloweeLibrary(@Param("me") Member me,
                                                  @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
                                                  @Param("cursorId") Long cursorId,
                                                  Pageable pageable);

    // 최근 감상 후보 (Cold-start 폴백 — 관심 장르를 아직 모을 수 없는 회원용)
    @Query("""
        SELECT r FROM Review r JOIN FETCH r.member JOIN FETCH r.book
        WHERE r.isDeleted = false
        AND r.reviewVisibility = 'PUBLIC'
        AND r.reviewStatus = 'PUBLISHED'
        AND r.createdAt >= :since
        AND r.member <> :me
        AND (:cursorCreatedAt IS NULL
             OR r.createdAt < :cursorCreatedAt
             OR (r.createdAt = :cursorCreatedAt AND r.reviewId < :cursorId))
        ORDER BY r.createdAt DESC, r.reviewId DESC
    """)
    List<Review> findPopularRecent(@Param("since") LocalDateTime since,
                                   @Param("me") Member me,
                                   @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
                                   @Param("cursorId") Long cursorId,
                                   Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE Review r SET r.likeCount = r.likeCount + 1 WHERE r.reviewId = :id")
    void increaseLikeCount(@Param("id") Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE Review r SET r.likeCount = r.likeCount - 1 WHERE r.reviewId = :id AND r.likeCount > 0")
    void decreaseLikeCount(@Param("id") Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE Review r SET r.commentCount = r.commentCount + 1 WHERE r.reviewId = :id")
    void increaseCommentCount(@Param("id") Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE Review r SET r.commentCount = r.commentCount - 1 WHERE r.reviewId = :id AND r.commentCount > 0")
    void decreaseCommentCount(@Param("id") Long id);
}
