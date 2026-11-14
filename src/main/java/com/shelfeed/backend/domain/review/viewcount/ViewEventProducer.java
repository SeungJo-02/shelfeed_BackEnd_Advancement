package com.shelfeed.backend.domain.review.viewcount;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 조회 이벤트를 Kafka로 보낸다. 요청 스레드는 여기서 끝나고 MySQL은 건드리지 않는다.
 *
 * <p>kafka-enabled=false(기본값)이면 빈을 만들지 않는다. 운영에는 브로커가 없으므로
 * 빈이 떠 있기만 해도 의미가 없다. {@link ViewCountService}는 ObjectProvider로 받아
 * 빈이 없는 경우를 이미 다룬다.
 */
@Slf4j
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "shelfeed.view-count.kafka-enabled", havingValue = "true")
@RequiredArgsConstructor
public class ViewEventProducer {

    public static final String TOPIC = "review.viewed";

    private final KafkaTemplate<String, ViewEvent> kafkaTemplate;

    /** 파티션 키를 reviewId로 둔다. 같은 글의 이벤트가 한 파티션에 모여 순서가 보장된다. */
    public void send(ViewEvent event) {
        kafkaTemplate.send(TOPIC, String.valueOf(event.reviewId()), event);
    }
}
