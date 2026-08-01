package com.shelfeed.backend.domain.notification.repository;

import com.shelfeed.backend.domain.notification.entity.Notification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    // actor는 더 이상 연관관계가 아니므로 JOIN FETCH할 수 없다.
    // 호출측(NotificationService)이 actorId를 모아 한 번의 IN 쿼리로 조회해 조립한다.
    @Query("""
            SELECT n FROM Notification n
            WHERE n.receiverId = :receiverId
              AND n.isDeleted = false
              AND (:cursor IS NULL OR n.notificationId < :cursor)
            ORDER BY n.notificationId DESC
            """)
    List<Notification> findMyNotifications(@Param("receiverId") Long receiverId,
                                           @Param("cursor") Long cursor,
                                           Pageable pageable);

    @Query("""
            SELECT COUNT(n) FROM Notification n
            WHERE n.receiverId = :receiverId
              AND n.isDeleted = false
              AND n.isRead = false
            """)
    long countUnread(@Param("receiverId") Long receiverId);

    @Modifying
    @Query("""
            UPDATE Notification n
            SET n.isRead = true
            WHERE n.receiverId = :receiverId
              AND n.isDeleted = false
              AND n.isRead = false
            """)
    int markAllAsRead(@Param("receiverId") Long receiverId);
}
