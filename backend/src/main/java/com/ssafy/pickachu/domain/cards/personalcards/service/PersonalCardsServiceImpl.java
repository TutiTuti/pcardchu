package com.ssafy.pickachu.domain.cards.personalcards.service;

import com.datastax.oss.driver.shaded.guava.common.reflect.TypeToken;
import com.ssafy.pickachu.domain.auth.PrincipalDetails;
import com.ssafy.pickachu.domain.auth.jwt.JwtUtil;
import com.ssafy.pickachu.domain.cards.personalcards.dto.*;
import com.ssafy.pickachu.domain.cards.personalcards.entity.CodefToken;
import com.ssafy.pickachu.domain.cards.personalcards.entity.PersonalCards;
import com.ssafy.pickachu.domain.cards.personalcards.mapper.PersonalCardsMapper;
import com.ssafy.pickachu.domain.cards.personalcards.repository.CodefRepository;
import com.ssafy.pickachu.domain.cards.personalcards.repository.PersonalCardsRepository;
import com.ssafy.pickachu.domain.cards.recommend.dto.SimpleCard;
import com.ssafy.pickachu.domain.cards.recommend.entity.CardInfo;
import com.ssafy.pickachu.domain.cards.recommend.entity.Cards;
import com.ssafy.pickachu.domain.cards.recommend.repository.CardInfoRepository;
import com.ssafy.pickachu.domain.cards.recommend.repository.CardsAggregation;
import com.ssafy.pickachu.domain.cards.recommend.repository.CardsRepository;
import com.ssafy.pickachu.domain.statistics.dto.SimpleCardHistory;
import com.ssafy.pickachu.domain.statistics.entity.CardHistoryEntity;
import com.ssafy.pickachu.domain.statistics.mapper.StatisticsMapper;
import com.ssafy.pickachu.domain.statistics.repository.CardHistoryEntityRepository;
import com.ssafy.pickachu.domain.statistics.service.CardHistoryService;
import com.ssafy.pickachu.domain.user.entity.User;
import com.ssafy.pickachu.domain.user.repository.UserRepository;
import com.ssafy.pickachu.global.codef.CodefApi;
import com.ssafy.pickachu.global.exception.ErrorCode;
import com.ssafy.pickachu.global.exception.ErrorException;
import com.ssafy.pickachu.global.util.JasyptUtil;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.google.gson.Gson;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.simple.parser.ParseException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import java.io.IOException;
import java.lang.reflect.Type;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;


@Slf4j
@RequiredArgsConstructor
@Service
public class PersonalCardsServiceImpl implements PersonalCardsService {

    private final PersonalCardsRepository personalCardsRepository;
    private final CardsRepository cardsRepository;
    private final CardInfoRepository cardInfoRepository;
    private final UserRepository userRepository;
    private final CardHistoryEntityRepository cardHistoryRepository;
    private final JwtUtil jwtUtil;
    private final PersonalCardsMapper personalCardsMapper;
    private final CodefRepository codefRepository;
    private final CodefApi codefApi;

    private final CardHistoryService cardHistoryService;
    private final JasyptUtil jasyptUtil;
    private final StatisticsMapper statisticsMapper;
    private final CardsAggregation cardsAggregation;

    private final String zeppelinUrl = "<zeppelin server url>:18888";
    private RestTemplate restTemplate = new RestTemplate();

    private void runNotebook() {
        String endpoint = zeppelinUrl + "/api/notebook/job/2JSXPJ6AT";
        restTemplate.postForObject(endpoint, null, Map.class);
    }

    private final Gson gson = new Gson();

    @Override
    public void DeleteMyCards(PrincipalDetails principalDetails, String cardid) {

        User user = userRepository.findById(principalDetails.getUserDto().getId())
            .orElseThrow(() -> new ErrorException(ErrorCode.USER_NOT_FOUND));

        PersonalCards personalCards = personalCardsRepository.findPersonalCardsByUserIdAndCardsIdAndUseYN(user.getId(), cardid, "Y")
            .orElseThrow(() -> new ErrorException(ErrorCode.PERSONAL_CARD_NOT_FOUND));

        personalCards.setUseYN("N");
        personalCardsRepository.save(personalCards);
    }

    @Override
    public List<SimplePersonalCardsRes> GetPersonalCardsList(PrincipalDetails principalDetails) {

        User user = userRepository.findById(principalDetails.getUserDto().getId())
            .orElseThrow(() -> new ErrorException(ErrorCode.USER_NOT_FOUND));

        List<SimplePersonalCardsRes> simplePersonalCardsRes = new ArrayList<>();

        List<PersonalCards> personalCardsList = personalCardsRepository.findAllByUserIdAndUseYN(user.getId(), "Y");
        personalCardsList.forEach(personalCards -> {

            SimplePersonalCardsRes tmpRes = personalCardsMapper.toSimplePersonalCardsRes(personalCards);

            String cardNo = jasyptUtil.decrypt(tmpRes.getCardNo());
            String maskedCardNumber = cardNo.replaceAll("(\\d{4}-)(\\d{4}-)(\\d{4}-)(\\d{4})", "$1****-****-$4");
            tmpRes.setCardNo(maskedCardNumber);

            Optional<Cards> cards = cardsRepository.findById(personalCards.getCardsId());
            cards.ifPresent(card -> {{
                tmpRes.setCardImage(card.getImageUrl());
            }});
            simplePersonalCardsRes.add(tmpRes);
        });
        return simplePersonalCardsRes;
    }

    @Override
    public void RegisterMyCards(PrincipalDetails principalDetails, RegisterCardsReq registerCardsReq) {

        User user = userRepository.findById(principalDetails.getUserDto().getId())
            .orElseThrow(() -> new ErrorException(ErrorCode.USER_NOT_FOUND));

        // XXX Codef Token 가져오기/ null -> 생성
        CodefToken codefToken = codefRepository.findById(1)
            .orElseGet(() -> {
                CodefToken token = CodefToken.builder()
                    .id(1)
                    .token(codefApi.GetToken())
                    .updateTime(LocalDateTime.now())
                    .build();
                return  token;
            });
        codefRepository.saveAndFlush(codefToken);


        // XXX 토큰 유효 7일 -> 지나면 갱신
        if (codefToken.getUpdateTime().plusDays(7).isBefore(LocalDateTime.now())) {
            codefToken.setToken(codefApi.GetToken());
            codefRepository.save(codefToken);
        }
        
        // XXX 등록된 카드인지 확인
        List<PersonalCards> personalCardList = personalCardsRepository.findAllByUserIdAndCardCompanyAndUseYN(user.getId(), registerCardsReq.getCardCompany(), "Y");
        for (PersonalCards card : personalCardList) {
            if (jasyptUtil.decrypt(card.getCardNo()).equals(registerCardsReq.getCardNo())){
                throw new ErrorException(ErrorCode.DUPLICATE_CARD_NO);
            }
        }
        
        

        // XXX 유저에게 connectedId 존재 여부 확인
        if (user.getConnectedId() == null) {
            try {
                String newConnectedId = codefApi.GetConnectedToken(registerCardsReq, codefToken.getToken());
                user.setConnectedId(jasyptUtil.encrypt(newConnectedId));
                SaveUser(user); //  다른 Transactional에서 ConnectedId 저장 -> rollBack 안됨

            } catch (NoSuchPaddingException | IllegalBlockSizeException | NoSuchAlgorithmException |
                     InvalidKeySpecException | BadPaddingException | InvalidKeyException | IOException |
                     ParseException | InterruptedException ignore) {
            }
        }

        // XXX 등록한 은행 리스트 가져오기
        List<String> bankList = null;
        try {
            bankList =codefApi.GetAccountList(jasyptUtil.decrypt(user.getConnectedId()), codefToken.getToken());
        } catch (IOException | ParseException | InterruptedException ignore) {

        }
        // XXX 없으면 은행 등록
        if (!bankList.contains(registerCardsReq.getCardCompany())){
            try {
                String newConnectedId = codefApi.AddBankInConnectedId(registerCardsReq, user, codefToken.getToken());
                user.setConnectedId(jasyptUtil.encrypt(newConnectedId));
                SaveUser(user);

            } catch (NoSuchPaddingException | IllegalBlockSizeException | NoSuchAlgorithmException |
                     InvalidKeySpecException | BadPaddingException | InvalidKeyException | IOException |
                     ParseException | InterruptedException ignore) {}
        }



        // XXX 내가 등록한 카드 다 가지고 오기 >> 없으면 내 카드가 아닌 것이구나??
        String userCards;
        Boolean myCards = true;
        try {
            userCards = codefApi.GetCardsName(registerCardsReq, user, codefToken.getToken());

        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (ParseException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        JSONObject usercardsJson = new JSONObject(userCards);
        JSONArray cardsListArray = new JSONArray();

        try {
            if (usercardsJson.get("data") instanceof JSONArray) {
                // 'data'가 배열인 경우
                cardsListArray = usercardsJson.getJSONArray("data");

            } else if (usercardsJson.get("data") instanceof JSONObject) {
                // 'data'가 객체인 경우
                cardsListArray.put(usercardsJson);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        // mongoDB : 크롤링한 카드 이름과 비교하여 찾기
        String cardNameTarget = "Card"; // 임시 카드 이름
        for (Object o : cardsListArray) {

            Map<String, Object> o1 = gson.fromJson(o.toString(), Map.class);

            String maskedStr1 = null;
            // 카드 조회 1개면 Object N개 List<Object> 로 옴
            try{
                Map<String, String> data = (Map<String, String>)o1.get("data");
                maskedStr1 = data.get("resCardNo");
                cardNameTarget = data.get("resCardName");
                cardNameTarget = cardNameTarget.replaceAll("[`~!@#$%^&*()_|+\\-=?;:'\",.<>\\{\\}\\[\\]\\\\\\/ ]", "");
            }catch (NullPointerException ignore){
                maskedStr1 = (String)o1.get("resCardNo");
                cardNameTarget = (String)o1.get("resCardName");
                cardNameTarget = cardNameTarget.replaceAll("[`~!@#$%^&*()_|+\\-=?;:'\",.<>\\{\\}\\[\\]\\\\\\/ ]", "");
            }
            //
            String maskedStr2 = registerCardsReq.getCardNo().replace("-", "");
            int matchingChars = 0;
            for (int i = 0; i < maskedStr1.length(); i++) {
                if (maskedStr1.charAt(i) == maskedStr2.charAt(i)) {
                    matchingChars++;
                }
            }

            maskedStr1 = maskedStr1.replaceAll("\\*", "");
            if(maskedStr2.length() - (maskedStr2.length()-maskedStr1.length()) == matchingChars){
                // 일치 -> 내 카드를 찾음 >> 어떤 카드 이름인지 알 수있다
                myCards = false;
                break;
            }
        }
        if (myCards){
            throw new ErrorException(ErrorCode.NOT_MY_CARDS);
        }

        String cardsIdTarget = "0000";  // 임시 카드 타겟
        // MongoDB 카드 Id와  매칭 하기
        Optional<Cards> optionalCards = cardsRepository.findByImageNameRegex(cardNameTarget);
        if (optionalCards.isPresent()){
            cardNameTarget = optionalCards.get().getCardName();
            cardsIdTarget = optionalCards.get().getId();
        }


        // XXX 카드 저장 하기
        PersonalCards personalCards = PersonalCards.builder()
            .name(cardNameTarget)
            .cardCompany(registerCardsReq.getCardCompany())
            .cardNo(jasyptUtil.encrypt(registerCardsReq.getCardNo()))
            .userId(user.getId())
            .cardCompanyId(jasyptUtil.encrypt(registerCardsReq.getCardCompanyId()))
            .cardCompanyPw(jasyptUtil.encrypt(registerCardsReq.getCardCompanyPw()))
            .cardsId(cardsIdTarget)
            .build();
        personalCardsRepository.saveAndFlush(personalCards);



        // 현재 날짜를 가져오기
        Calendar calendar = Calendar.getInstance();
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd");
        // 어제 날짜까지
        calendar.add(Calendar.DATE, -1); // 어제
        String endDay = dateFormat.format(calendar.getTime());

        calendar.add(Calendar.MONTH, -1); // 한 달을 빼서 지난달로 설정
        calendar.set(Calendar.DAY_OF_MONTH, 1); // 그 달의 첫 번째 날로 설정
        String startDay = dateFormat.format(calendar.getTime());

        // XXX 사용내역 가지고 오기 (저번달 1일 ~ 어제)
        try {
            String payListResult = codefApi.GetUseCardList(registerCardsReq, user, codefToken.getToken(),startDay,endDay);
            // 저장하러 가기
            cardHistoryService.saveCardHistories(payListResult, user, personalCards.getId());
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (ParseException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        // 분산 실행
        runNotebook();
    }

    @Override
    public PersonalCardsDetailRes GetPersonaLCardDetail(PrincipalDetails principalDetails, long cardId) {
        User user = userRepository.findById(principalDetails.getUserDto().getId())
            .orElseThrow(() -> new ErrorException(ErrorCode.USER_NOT_FOUND));

        PersonalCards personalCards = personalCardsRepository.findById(cardId)
            .orElseThrow(() -> new ErrorException(ErrorCode.PERSONAL_CARD_NOT_FOUND));
        Cards cards = cardsRepository.findById(personalCards.getCardsId())
            .orElseThrow(() -> new ErrorException(ErrorCode.CARDS_NOT_FOUND));

        CardInfo cardInfo = cardInfoRepository.findCardInfoByCardId(cards.getId())
            .orElseThrow(() -> new ErrorException(ErrorCode.CARDINFO_NOT_FOUND));

        LocalDate today = LocalDate.now();
        LocalDate firstDayOfMonth = today.withDayOfMonth(1); // 이번 달의 첫 번째 날짜
        LocalDate yesterday = today.minusDays(1); // 이번 달의 첫 번째 날짜
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");

        String startDate = firstDayOfMonth.format(formatter);
        String endDate = today.format(formatter);
        String yesterDay = yesterday.format(formatter);

        List<CardHistoryEntity> monthlyUsageDetails = cardHistoryRepository.findAllByDateRangeOrderedByDateAndTimeDesc((int)user.getId(), startDate, endDate);

        // date를 먼저 비교하고, date가 같으면 time으로 비교
        monthlyUsageDetails = monthlyUsageDetails.stream()
            .sorted(Comparator.comparing(CardHistoryEntity::getDate).thenComparingInt(CardHistoryEntity::getTime).reversed())
            .toList();

        int useMoney = 0;
        List<SimpleCardHistory> secondReturnValue = new ArrayList<>();
        for (CardHistoryEntity cardHistoryEntity : monthlyUsageDetails) {
            useMoney = useMoney + cardHistoryEntity.getAmount();

            if (cardHistoryEntity.getDate().equals(endDate) || cardHistoryEntity.getDate().equals(yesterDay)){
                secondReturnValue.add(statisticsMapper.ToSimpleCardHistory(cardHistoryEntity));
            }
        }
        String useValue = cardHistoryService.CalculateBenefit(cardInfo, monthlyUsageDetails);
        Type type = new TypeToken<Map<String, Integer>>(){}.getType();
        Map<String, Integer> useBenefit = gson.fromJson(useValue, type);

        return PersonalCardsDetailRes.builder()
            .cardImage(cards.getImageUrl())
            .cardName(cards.getCardName())
            .cardCompany(cards.getOrganization_id())
            .useMoneyMonth(useMoney)
            .todayUseHistory(secondReturnValue)
            .useBenefit(useBenefit)
            .build();

    }

    @Override
    public RecommendPersonalCardRes GetRecommendPersonalCard(PrincipalDetails principalDetails) {
        User user = userRepository.findById(principalDetails.getUserDto().getId())
                .orElseThrow(() -> new ErrorException(ErrorCode.USER_NOT_FOUND));

        // XXX 저번달 1일 ~~ 오늘 까지 결재 내역 가지고 오기
        LocalDate today = LocalDate.now();
        LocalDate firstDayOfMonth = today.minusMonths(1).withDayOfMonth(1);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");

        String startDate = firstDayOfMonth.format(formatter);
        String endDate = today.format(formatter);

        // XXX userId 기준  시작날짜 < x < 종료날짜   결재 내역 가지고 오기
        List<CardHistoryEntity> cardHistoryEntities = cardHistoryRepository.findAllByDateRangeOrderedByDateAndTimeDesc((int) user.getId(), startDate, endDate);
        if(cardHistoryEntities.isEmpty()){
            throw new ErrorException(ErrorCode.CARDINFO_NOT_FOUND);
        }

        Map<String, Integer> consumptionHistory = new HashMap<>();
        for (CardHistoryEntity cardHistory : cardHistoryEntities) {
            int useMoney = consumptionHistory.getOrDefault(cardHistory.getCategory(), 0);
            consumptionHistory.put(cardHistory.getCategory(), useMoney+cardHistory.getAmount());
        }
        // XXX 사용 금액 순 카테고리 정렬 reverse True
        List<Map.Entry<String, Integer>> sortedEntries = consumptionHistory.entrySet()
                .stream()
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .collect(Collectors.toList());

        List<RecommendCard> returnValue = new ArrayList<>();

        // 계산 여부 체크용 MAP
        Map<String, Integer> cardCheck = new HashMap<String, Integer>();

        // TOP3 카테고리 조회
        for (int i = 0; i < 3; i++) {
            String category;
            try {
                category = sortedEntries.get(i).getKey();
            } catch (IndexOutOfBoundsException ignore) {
                continue;
            }
            // 해당 카테고리 혜택 카드 전체 조회
            List<CardInfo> cardInfos = cardsAggregation.GetCardsCategoList(category);
            // 이미 계산한 카드 중복 제거
            List<CardInfo> distinctCardInfos = new ArrayList<>();
            for (CardInfo cardInfo : cardInfos) {
                if (cardCheck.getOrDefault(cardInfo.getCardId(), 0) == 0) {
                    distinctCardInfos.add(cardInfo);
                    cardCheck.put(cardInfo.getCardId(), 1);
                }
            }

            // 카드별 받는 혜택 계산해서 넣기
            List<SimpleCard> addBenefitCal = new ArrayList<>();
            for (CardInfo cardInfo : distinctCardInfos) {
                // 카드 카테고리 별 할인 적용액 JSON
                String useValue = cardHistoryService.CalculateBenefit(cardInfo, cardHistoryEntities);
                Type type = new TypeToken<Map<String, Integer>>() {
                }.getType();

                // 내 카드 사용 내역에서 k=카테고리 v=할인혜택이 계산되어있는 MAP
                Map<String, Integer> useBenefit = gson.fromJson(useValue, type); // Map으로 바꾸기

                // 해당 카드 사용 혜택
                int totalBenefitMoney = 0;
                for (Integer m : useBenefit.values()){
                    totalBenefitMoney += m;
                }

                // 해당 카테고리 사용 혜택 내용 가져오기
                int idx = cardInfo.getGroupCategory().indexOf(category);
                String key = cardInfo.getCategories().get(idx);

                Map<String, String> benefitContents;
                try {
                    benefitContents = (Map<String, String>) cardInfo.getContents().get(key).get(1);
                } catch (NullPointerException ignore) {
                    continue;
                }
                String categoryBenefit = benefitContents.get("benefitSummary");


                Cards cards = cardsRepository.findById(cardInfo.getCardId())
                        .orElseThrow(() -> new ErrorException(ErrorCode.CARDS_NOT_FOUND));

                addBenefitCal.add(SimpleCard.builder()
                        .cardId(cardInfo.getCardId())
                        .cardImg(cards.getImageUrl())
                        .cardName(cards.getCardName())
                        .cardCompany(cards.getOrganization_id())
                        .cardContent(categoryBenefit)
                        .useMoney(totalBenefitMoney)
                        .build());

            }

            // 총 할인 혜택 금액 기준으로 정렬
            addBenefitCal.sort(Comparator.comparingInt(SimpleCard::getUseMoney).reversed());

            RecommendCard topCategoryResult = RecommendCard.builder()
                    .category(category)
                    .total(sortedEntries.get(i).getValue())
                    .build();
            // 가장 혜택이 큰 카드 3개 넣기
            topCategoryResult.setCard((addBenefitCal.subList(0, Math.min(addBenefitCal.size(), 3))));

            returnValue.add(topCategoryResult);

        }

        return RecommendPersonalCardRes.builder()
                .name(user.getNickname())
                .discount(returnValue)
                .build();
    }


    @Transactional
    public void SaveUser(User user){
        userRepository.saveAndFlush(user);
    }

}
