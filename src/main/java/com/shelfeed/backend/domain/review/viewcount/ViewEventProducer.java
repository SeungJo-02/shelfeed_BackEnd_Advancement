package com.shelfeed.backend.domain.review.viewcount;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** 조회 이벤트를 Kafka로 보낸다. 요청 스레드는 여기서 끝나고 MySQL은 건드리지 않는다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ViewEventProducer {

    public static final String TOPIC = "review.viewed";

    private final KafkaTemplate<String, ViewEvent> kafkaTemplate;

    /** 파티션 키를 reviewId로 둔다. 같은 글의 이벤트가 한 파티션에 모여 순서가 보장된다. */
    public void send(ViewEvent event) {
        kafkaTemplate.send(TOPIC, String.valueOf(event.reviewId()), event);
    }
}
