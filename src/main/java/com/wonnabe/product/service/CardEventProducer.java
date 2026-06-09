package com.wonnabe.product.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Log4j2
public class CardEventProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    
    private final ObjectMapper objectMapper;

    private static final String TOPIC = "user-consumption-events";

    /**
     * 유저의 결제 내역(이벤트)을 카프카로 보내는 메서드
     * @param userId 유저 ID
     * @param consumptionData 결제 내역 데이터 
     */
    public void sendConsumptionEvent(String userId, Object consumptionData) {
        try {
            String jsonMessage = objectMapper.writeValueAsString(consumptionData);
            
            log.info("▶ [Kafka Producer] 카프카로 쪽지 발송 시도 - 유저: {}, 내용: {}", userId, jsonMessage);
            

            kafkaTemplate.send(TOPIC, userId, jsonMessage);
            
            log.info("▷ [Kafka Producer] 카프카로 쪽지 발송 완료! (비동기 처리라 바로 다음 코드로 넘어감)");
            
        } catch (Exception e) {
            log.error("❌ [Kafka Producer] 쪽지 발송 중 에러 발생: ", e);
        }
    }
}