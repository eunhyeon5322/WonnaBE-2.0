package com.wonnabe.product.controller;

import com.wonnabe.product.service.CardServiceImpl;
import com.wonnabe.product.dto.CardRecommendationResponseDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.kafka.core.KafkaTemplate;

@Log4j2
@Controller
@RequiredArgsConstructor
@RequestMapping("/api/cards")
public class CardController {

    private final CardServiceImpl cardService;
    private final KafkaTemplate<String, String> kafkaTemplate; 

    /**
     * [POST] 
     */
    @RequestMapping(value = "/payment", method = RequestMethod.POST)
    @ResponseBody
    public String simulatePayment(@RequestParam("userId") String userId) {
        log.info("--> [Legacy Controller] Payment event triggered for user: {}", userId);
        
        String fakeKafkaMessage = "{\"userId\":\"" + userId + "\", \"amount\":50000}";
        
        kafkaTemplate.send("user-consumption-events", fakeKafkaMessage);
        
        return "Success: Payment event sent to Kafka pipeline!";
    }

    /**
     * [GET] 
     */
    @RequestMapping(value = "/recommend", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<CardRecommendationResponseDTO> getRecommendedCards(@RequestParam("userId") String userId) {
        log.info("--> [Legacy Controller] Recommendation requested for user: {}", userId);
        
        CardRecommendationResponseDTO response = cardService.recommendCards(userId, 5);
        
        return ResponseEntity.ok(response);
    }
}