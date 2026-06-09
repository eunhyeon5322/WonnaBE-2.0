package com.wonnabe.product.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Log4j2
public class CardEventConsumer {

    // 가중치 계산 로직이 들어있는 기존 서비스
    private final CardServiceImpl cardService;

    // 데이터를 텍스트에서 자바 객체로 조립해 줄 도구
    private final ObjectMapper objectMapper;

    // 계산된 정답지(카드 추천 결과)를 저장할 Redis
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 카프카 창구를 실시간으로 감시하다가 쪽지가 오면 자동으로 실행되는 메서드
     */
    @KafkaListener(
        topics = "user-consumption-events", 
        groupId = "wonnabe-recommendation-group",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeConsumptionEvent(String message) {
        try {
            log.info("📩 [Kafka Consumer] 카프카 창구에서 실시간 결제 쪽지 낚아채기 성공!");

            // 1. 카프카에서 가져온 텍스트(JSON)를 다시 자바가 읽을 수 있는 Map(객체) 형태로 조립
            Map<String, Object> eventData = objectMapper.readValue(message, Map.class);
            
            // 2. 쪽지에서 "이게 누구 결제 내역인지" 유저 ID
            String userId = (String) eventData.get("userId");
            log.info("🎯 [Kafka Consumer] 분석 시작 -> 유저 ID: {}", userId);

            // 3. 사용자가 버튼을 누르기 전 백그라운드에서 가중치 알고리즘을 '미리' 실행
            // 기존에 작성되어 있던 CardServiceImpl의 추천 로직 메서드를 그대로 활용
            Object recommendationResult = cardService.recommendCards(userId, 5);
            log.info("⚙️ [Kafka Consumer] 가중치 추천 알고리즘 계산 완료!");

            // 4. [Redis 연동] 계산된 결과를 Redis 메모장에 "user:유저ID" 키로 저장
            String redisKey = "user:recommendation:" + userId;
            redisTemplate.opsForValue().set(redisKey, recommendationResult);
            
            log.info("💾 [Kafka Consumer] Redis 초고속 메모장에 결과 저장 완료! Key: {}", redisKey);

        } catch (Exception e) {
            log.error("❌ [Kafka Consumer] 쪽지를 읽어서 처리하는 중 에러 발생: ", e);
        }
    }
}