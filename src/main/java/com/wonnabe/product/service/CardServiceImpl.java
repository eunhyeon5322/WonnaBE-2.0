package com.wonnabe.product.service;

import com.wonnabe.product.domain.CardProductVO;
import com.wonnabe.product.domain.UserCardVO;
import com.wonnabe.product.dto.BasicUserInfoDTO;
import com.wonnabe.product.dto.CardApplyRequestDTO;

import static com.wonnabe.product.dto.CardProductDetailResponseDTO.*;
import static com.wonnabe.product.dto.CardRecommendationResponseDTO.*;

import com.wonnabe.product.dto.CardProductDetailResponseDTO;
import com.wonnabe.product.dto.CardProductDetailResponseDTO.CardInfo;
import com.wonnabe.product.dto.CardProductDetailResponseDTO.ComparisonChart;
import com.wonnabe.product.dto.CardProductDetailResponseDTO.Note;
import com.wonnabe.product.dto.CardRecommendationResponseDTO;
import com.wonnabe.product.dto.CardRecommendationResponseDTO.PersonaRecommendation;
import com.wonnabe.product.dto.CardRecommendationResponseDTO.RecommendedCard;
import com.wonnabe.product.dto.UserCardDTO;
import com.wonnabe.product.dto.UserCardDetailDTO;
import com.wonnabe.product.dto.UserInfoForCardDTO;
import com.wonnabe.product.mapper.CardMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.LocalDate;
import java.time.Period;
import java.util.Calendar;
import java.util.Date;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;


@Log4j2
@Service("cardServiceImpl")
@RequiredArgsConstructor
public class CardServiceImpl implements CardService {

    private final CardMapper cardMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    // 페르소나 이름 매핑
    private static final Map<Integer, String> PERSONA_NAMES = Map.ofEntries(
        Map.entry(1, "자린고비형"),
        Map.entry(2, "소확행형"),
        Map.entry(3, "YOLO형"),
        Map.entry(4, "경험 소중형"),
        Map.entry(5, "새싹 투자형"),
        Map.entry(6, "공격 투자형"),
        Map.entry(7, "미래 준비형"),
        Map.entry(8, "가족 중심형"),
        Map.entry(9, "루틴러형"),
        Map.entry(10, "현상 유지형"),
        Map.entry(11, "균형 성장형"),
        Map.entry(12, "대문자P형")
    );

    private static final Map<Integer, double[]> PERSONA_WEIGHTS = new HashMap<>() {{
        put(1, new double[]{0.05, 0.10, 0.30, 0.05, 0.50});   // 자린고비형
        put(2, new double[]{0.20, 0.30, 0.20, 0.10, 0.20});   // 소확행형
        put(3, new double[]{0.30, 0.35, 0.05, 0.20, 0.10});   // YOLO형
        put(4, new double[]{0.25, 0.40, 0.10, 0.15, 0.10});   // 경험 소비형
        put(5, new double[]{0.05, 0.10, 0.40, 0.05, 0.40});   // 새싹 투자형
        put(6, new double[]{0.35, 0.30, 0.05, 0.20, 0.10});   // 공격 투자형
        put(7, new double[]{0.20, 0.30, 0.15, 0.20, 0.15});   // 미래 준비형
        put(8, new double[]{0.15, 0.30, 0.20, 0.10, 0.25});   // 가족 중심형
        put(9, new double[]{0.10, 0.30, 0.20, 0.25, 0.15});   // 루틴러형
        put(10, new double[]{0.05, 0.10, 0.30, 0.10, 0.45});  // 현상 유지형
        put(11, new double[]{0.20, 0.20, 0.20, 0.20, 0.20});  // 균형 성장형
        put(12, new double[]{0.25, 0.25, 0.05, 0.35, 0.10});  // 대문자P형
    }};



    @Override
    public CardRecommendationResponseDTO recommendCards(String userId, int topN) {
        
        // -------------------------------------------------------------------
        // [WonnaBE 2.0 고도화] 1순위: Redis 초고속 메모장 먼저 확인하기
        // -------------------------------------------------------------------
        String redisKey = "user:recommendation:" + userId;
        try {
            // Redis에서 이 유저의 미리 계산된 추천 결과가 있는지 확인
            CardRecommendationResponseDTO cachedResult = (CardRecommendationResponseDTO) redisTemplate.opsForValue().get(redisKey);
            
            if (cachedResult != null) {
                log.info("🚀 [Redis Cache Hit] 대기 시간 0초! 백그라운드에서 미리 계산된 추천 결과를 반환합니다. 유저: {}", userId);
                return cachedResult;
            }
        } catch (Exception e) {
            log.error("⚠️ Redis 조회 중 예외 발생 (안전하게 기존 MySQL 로직으로 백업 진행): ", e);
        }

        // -------------------------------------------------------------------
        // 2순위: 만약 Redis에 데이터가 없다면? (기존 1.0 오리지널 로직 작동)
        // -------------------------------------------------------------------
        log.info("🐢 [Redis Cache Miss] 미리 계산된 결과가 없어 기존 MySQL 실시간 계산을 수행합니다. 유저: {}", userId);

        // 1. 사용자 정보 조회 (소득, 소비, 워너비 ID 등)
        UserInfoForCardDTO userInfo = cardMapper.findUserInfoForCardRecommend(userId);
        if (userInfo == null || userInfo.getSelectedWonnabeIds() == null || userInfo.getSelectedWonnabeIds().isEmpty()) {
            throw new NoSuchElementException("추천을 위한 사용자 정보가 부족합니다.");
        }

        // 내가 보유한 카드 ID 리스트
        List<Long> myCardIds = userInfo.getMyCardIds();
        List<Long> myProductId = cardMapper.findProductIdsByUserCardIds(myCardIds);

        // 2. 카드 상품 목록 조회
        List<CardProductVO> cardProducts = cardMapper.findAllCardProducts();
        if (cardProducts == null || cardProducts.isEmpty()) {
            throw new NoSuchElementException("추천할 카드 상품이 없습니다.");
        }

        // 3. 응답 객체
        CardRecommendationResponseDTO response = new CardRecommendationResponseDTO();
        response.setUserId(userId);
        response.setRecommendationsByPersona(new ArrayList<>());

        // 4. 각 워너비에 대해 추천
        for (Integer wannabeId : userInfo.getPersonaIds()) {
            double[] baseWeights = PERSONA_WEIGHTS.get(wannabeId).clone();
            double[] adjustedWeights = adjustWeightsByIncome(baseWeights, userInfo.getIncomeAnnualAmount());

            List<ProductWithScore> scoredCards = new ArrayList<>();
            for (CardProductVO card : cardProducts) {
                // 내가 가진 카드는 추천에서 제외
                if (myProductId != null && myProductId.contains((Long) card.getProductId())) {
                    continue;
                }

                // matchedFilters에 현재 워너비 ID가 없으면 제외 (1차 필터링)
                if (!card.getMatchedFilters().contains(wannabeId)) {
                    continue;
                }

                double score = calculateScore(card, adjustedWeights, userInfo.getPreviousConsumption());
                scoredCards.add(new ProductWithScore(card, score));
            }

            // 점수 내림차순 정렬
            scoredCards.sort((a, b) -> Double.compare(b.score, a.score));

            // 결과 객체 구성
            PersonaRecommendation personaRec = new PersonaRecommendation();
            personaRec.setPersonaId(wannabeId);
            personaRec.setPersonaName(PERSONA_NAMES.get(wannabeId));
            personaRec.setProducts(new ArrayList<>());

            for (int i = 0; i < Math.min(topN, scoredCards.size()); i++) {
                CardProductVO card = scoredCards.get(i).card;
                RecommendedCard item = RecommendedCard.builder()
                    .productType("card")
                    .cardId(Long.toString(card.getProductId()))
                    .cardName(card.getCardName())
                    .cardCompany(card.getCardCompany())
                    .cardType(card.getCardTypeLabel())
                    .matchScore((int) scoredCards.get(i).score)
                    .mainBenefit(card.getBenefitLimit())
                    .annualFeeDomestic(card.getAnnualFeeDomestic())
                    .annualFeeOverSeas(card.getAnnualFeeOverSeas())
                    .build();
                personaRec.getProducts().add(item);
            }

            response.getRecommendationsByPersona().add(personaRec);
        }

        return response;
    }

    // 소득/고용상태에 따른 가중치 조정
    public double[] adjustWeightsByIncome(double[] weights, double incomeAnnualAmount) {
        double[] adjusted = weights.clone();

        // 소득원별 조정
        if (incomeAnnualAmount >= 48000000.00) {
            adjusted[0] += 0.02;  // 확장성
            adjusted[1] += 0.02;  // 혜택 범위
            adjusted[2] -= 0.02; // 전월 실적
            adjusted[4] -= 0.02; // 연회비
        } else if (incomeAnnualAmount < 24000000.00) {
            adjusted[0] -= 0.02;  // 확장성
            adjusted[1] -= 0.02;  // 혜택 범위
            adjusted[2] += 0.02; // 전월 실적
            adjusted[4] += 0.02; // 연회비
        }

        // 정규화 (합 = 1)
        return normalizeWeights(adjusted);
    }

    // 카드 활용 점수 계산
    public int calculateUsageScore(int performanceRate) {
        if (performanceRate >= 100) return 5;
        if (performanceRate >= 80) return 4;
        if (performanceRate >= 60) return 3;
        if (performanceRate >= 40) return 2;
        return 1;
    }

    // 가중치 정규화
    public double[] normalizeWeights(double[] weights) {
        double sum = Arrays.stream(weights).sum();
        if (sum == 0) {
            return weights;
        }
        return Arrays.stream(weights).map(w -> w / sum).toArray();
    }

    // 점수 계산
    public double calculateScore(CardProductVO card, double[] weights, double amount) {
        List<Integer> score = card.getCardScores();
        int performanceRate = calculatePerformanceRate(card.getPerformanceCondition(), amount);
        int usageScore = calculateUsageScore(performanceRate);
        score.set(3, usageScore);
        String updatedScore = score.toString();  // 예: [2, 3, 5, 4, 5]
        card.setCardScore(updatedScore);
        return (weights[0] * score.get(0) +
            weights[1] * score.get(1) +
            weights[2] * score.get(2) +
            weights[3] * score.get(3) +
            weights[4] * score.get(4)) * 20;
    }

    // 카드 계약기간을 계산함
    public int calculateTerm(LocalDate issueDate, LocalDate expiryDate) {
        Period period = Period.between(issueDate, expiryDate);
        return period.getYears() * 12 + period.getMonths();
    }

    // 카드 활용도를 계산함
    public int calculatePerformanceRate(long performanceCondition, double monthlyUsage) {
        double performanceRate = monthlyUsage / performanceCondition * 100;
        if (performanceRate > 100) {
            performanceRate = 100;
        }
        return (int) performanceRate;
    }


    @Override
    public UserCardDetailDTO findUserCardDetail(long productId, String userId) {
        // 사용자 정보를 가져옴
        UserCardDTO userCardDTO = cardMapper.findUserCardDetailById(productId, userId);
        // 카드 계약 기간 계산
        int term = calculateTerm(userCardDTO.getIssueDate(), userCardDTO.getExpiryDate());
        // 카드 실적율 계산
        int performanceRate = calculatePerformanceRate(userCardDTO.getPerformanceCondition(), userCardDTO.getMonthlyUsage());
        // 반환할 객체 생성
        UserCardDetailDTO userCardDetailDTO = UserCardDetailDTO.custom(userCardDTO, term, performanceRate);
        // null 일 경우 예외 아니면 해당 객체 반환
        return Optional.ofNullable(userCardDetailDTO)
                .orElseThrow(NoSuchElementException::new);
    }

    // 카드 번호 생성 함수
    private String getNextCardNumber() {
        String lastCardNumber = cardMapper.findLastCardNumber();
        long nextNum = 1L;

        if (lastCardNumber != null) {
            String digits = lastCardNumber.replaceAll("-", "");
            nextNum = Long.parseLong(digits) + 1;
        }

        String nextDigits = String.format("%016d", nextNum);  // 항상 16자리 유지
        return nextDigits.replaceAll("(.{4})(?=.)", "$1-");
    }

    @Override
    @Transactional
    public void applyUserCard(CardApplyRequestDTO cardApplyRequestDTO, String userId){
        Calendar calendar = Calendar.getInstance(); // 현재 날짜
        calendar.add(Calendar.YEAR, 5); // 5년 뒤 카드 만료
        // 계좌 Id 조회
        Long accountId = cardMapper.getAccountId(cardApplyRequestDTO.getLinkedAccount(), userId);
        if (accountId == null) {
            throw new NoSuchElementException("해당 계좌를 보유하고 있지 않습니다.");
        }

        // 마지막으로 입력된 카드 번호 조회
        String lastCardNumber = getNextCardNumber();

        // 카드 상품 조회
        CardProductVO product = cardMapper.findById(Long.parseLong(cardApplyRequestDTO.getCardId()));

        // 사용자에 맞는 형식으로 변환
        UserCardVO card = UserCardVO.builder()
                .userId(userId)
                .productId(product.getProductId())
                .monthlyUsage(0)
                .issueDate(new Date())
                .expiryDate(calendar.getTime())
                .performanceCondition(product.getPerformanceCondition())
                .cardNumber(lastCardNumber)
                .accountId(accountId)
                .build();

        // 사용자 카드 신청
        cardMapper.insertUserCard(card);
        // 신청 후 발급 된 아이디 확인
        long id = card.getId();
        // 사용자 정보에 카드 추가
        cardMapper.updateUserCardInfo(id, card.getUserId());

        // 사용자 카드 목록 확인
        String myCardIds = cardMapper.getMyCardIdsJson(card.getUserId());
        // 사용자가 등록한 카드 확인
        UserCardVO cardCheck = cardMapper.findUserCardByproductId(card.getProductId(), card.getUserId());
        // 비워 있을 시 등록 실패 반환
        if (cardCheck == null && myCardIds.isEmpty()) {
            throw new IllegalStateException("카드 등록에 실패했습니다.");
        }
    }

    // 내부 클래스: 상품과 점수
    private static class ProductWithScore {
        CardProductVO card;
        double score;

        ProductWithScore(CardProductVO card, double score) {
            this.card = card;
            this.score = score;
        }
    }

    private static final Map<String, String> CATEGORY_LABELS = Map.of(
        "food", "식비",
        "transport", "교통",
        "shopping", "쇼핑",
        "financial", "금융",
        "other", "기타"
    );

    public static String translateCategories(List<String> mainCategories) {
        return mainCategories.stream()
            .map(CATEGORY_LABELS::get)
            .filter(Objects::nonNull) // 혹시 매핑되지 않은 값 제외
            .collect(Collectors.joining(", "));
    }

    @Override
    public CardProductDetailResponseDTO findProductDetail(long productId, String userId) {
        // 내가 보유 중인 카드 상품 id
        List<Long> myCardIds = cardMapper.findProductIdsByUserId(userId);

        // 선택한 카드 상품 조회
        CardProductVO card = cardMapper.findById(productId);

        // 없을 시 예외 처리
        if (card == null) {
            throw new NoSuchElementException("해당 카드 상품은 존재하지 않습니다.");
        }

        // 사용자 정보 조회
        BasicUserInfoDTO user = cardMapper.findBasicUserInfoById(userId);

        if (user == null) {
            throw new NoSuchElementException("사용자의 정보를 찾을 수 없습니다.");
        }

        // 현재 카드 상품에 대한 점수와 가중치 계산
        double[] baseWeights = PERSONA_WEIGHTS.get(user.getNowMeId()).clone();
        double[] adjustedWeights = adjustWeightsByIncome(baseWeights, user.getIncomeAnnualAmount());

        // 카드 매칭 점수 계산
        double cardScore = calculateScore(card, adjustedWeights, user.getPreviousConsumption());

        // 내 카드 상품 정보를 가져옴

        List<CardProductVO> myCards = new ArrayList<>();
        if (myCardIds != null && !myCardIds.isEmpty()) {
            myCards = cardMapper.findProductsByIds(myCardIds);
        }

        // 카드 상세 정보를 위한 DTO
        CardProductDetailResponseDTO response = new CardProductDetailResponseDTO();

        // 관심 상품 등록 여부 확인
        boolean isWished = user.getMyFavorite().contains(productId);

        // 내 카드 점수 업데이트
        int cardPerformanceScore = calculatePerformanceRate(card.getPerformanceCondition(), user.getPreviousConsumption());
        int cardUsageScore = calculateUsageScore(cardPerformanceScore);

        List<Integer> scores = card.getCardScores();
        scores.set(3, cardUsageScore);
        String updatedScore = scores.stream()
            .map( score -> score * 20)
            .map(String::valueOf)
            .collect(Collectors.joining(", ", "[", "]"));
        card.setCardScore(updatedScore);

        List<String> label = List.of(
            "확장성",
            "혜택 범위",
            "전월 실적",
            "카드 활용도",
            "연회비 부담"
        );

        // 카드 상품 정보 저장
        CardInfo cardInfo = CardInfo.builder()
            .cardId(card.getProductId())
            .cardName(card.getCardName())
            .cardCompany(card.getCardCompany())
            .matchScore((int)cardScore)
            .mainBenefit(card.getBenefitLimit())
            .cardType(card.getCardType().toValue())
            .benefitSummary(card.getBenefitSummary())
            .isWished(isWished)
            .labels(label)
            .currentUserData(card.getCardScores())
            .build();

        response.setCardInfo(cardInfo);

        // 비교 차트
        List<ComparisonChart> comparisonCharts = new ArrayList<>();

        // 내 카드와 해당 상품 비교 차트에 대한 정보
        for (CardProductVO myCard : myCards) {

            // 카드 활용 점수 설정
            int myPerformanceRate = calculatePerformanceRate(myCard.getPerformanceCondition(), user.getPreviousConsumption());
            int myCardUsageScore = calculateUsageScore(myPerformanceRate);

            List<Integer> myScores = myCard.getCardScores();
            myScores.set(3, myCardUsageScore);
            String updatedMyScore = myScores.stream()
                .map( myScore -> myScore * 20)
                .map(String::valueOf)
                .collect(Collectors.joining(", ", "[", "]"));
            myCard.setCardScore(updatedMyScore);

            // 비교 차트 설정
            ComparisonChart comparisonChart = ComparisonChart.builder()
                .compareId(myCard.getProductId())
                .compareName(myCard.getCardName())
                .recommendedProductData(myCard.getCardScores())
                .build();

            comparisonCharts.add(comparisonChart);
        }

        if (!comparisonCharts.isEmpty()) {
            response.setComparisonChart(comparisonCharts);
        }

        String mainCategory = translateCategories(card.getMainCategories());
        String category = "혜택 적용 범위: " + mainCategory;
        String usage = "";
        if (!card.getAnnualFeeDomestic().equals("해당안함")) {
            usage += "국내 전용";
            if (!card.getAnnualFeeOverSeas().equals("해당안함")) {
                usage += " / 해외 겸용";
            }
        } else {
            usage += "해외 겸용";
        }
        String annual_fee = "국내 연회비: "+ card.getAnnualFeeDomestic()
            + " / 해외 연회비: " + card.getAnnualFeeOverSeas();

        Note note = Note.builder()
            .category(category)
            .previousMonthSpending(card.getPerformanceConditionDescription())
            .usage(usage)
            .annualFee(annual_fee)
            .build();

        response.setNote(note);

        return response;
    }
}
