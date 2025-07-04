package com.ll.demo03.domain.task.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ll.demo03.domain.task.dto.ImageRequest;
import com.ll.demo03.domain.task.dto.ImageRequestMessage;
import com.ll.demo03.domain.task.dto.WebhookEvent;
import com.ll.demo03.domain.task.service.ImageMessageProducer;
import com.ll.demo03.domain.member.entity.Member;
import com.ll.demo03.domain.oauth.entity.PrincipalDetails;
import com.ll.demo03.domain.webhook.GeneralImageWebhookProcessor;
import com.ll.demo03.global.dto.GlobalResponse;
import com.ll.demo03.global.error.ErrorCode;
import com.ll.demo03.global.exception.CustomException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Transactional
@RestController
@RequestMapping("/api/images")
@Slf4j
public class TaskController {

    @Value("${custom.webhook-url}")
    private String webhookUrl;

    @Value("${openai.api.key}")
    private String openAiApiKey;

    private final ImageMessageProducer imageMessageProducer;
    private final GeneralImageWebhookProcessor generalImageWebhookProcessor;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;

    @PostMapping(value = "/create")
    @PreAuthorize("isAuthenticated()")
    public GlobalResponse createImage(
            @RequestBody ImageRequest imageRequest,
            @AuthenticationPrincipal PrincipalDetails principalDetails
    ) {
        Member member = principalDetails.user();
        int credit = member.getCredit();
        if (credit <= 0) {
            throw new CustomException(ErrorCode.NO_CREDIT);
        }

        String prompt = imageRequest.getPrompt();
        String ratio = imageRequest.getRatio();
        String cref = imageRequest.getCrefUrl();
        String sref = imageRequest.getSrefUrl();

        boolean isSafe = moderatePrompt(prompt);
        if (!isSafe) {
            log.warn("Prompt 검열 실패: {}", prompt);
            throw new CustomException(ErrorCode.INAPPROPRIATE_CONTENT);
        }

        String newPrompt = modifyPrompt(prompt);

        String finalPrompt = (newPrompt == null || newPrompt.isBlank()) ? prompt : newPrompt;

        try {
            ImageRequestMessage requestMessage = new ImageRequestMessage(
                    finalPrompt,
                    ratio,
                    cref,
                    sref,
                    webhookUrl + "/api/images/webhook",
                    member.getId(),
                    prompt
            );


            imageMessageProducer.sendImageCreationMessage(requestMessage);

            return GlobalResponse.success();
        } catch (Exception e) {
            log.error("이미지 생성 요청 중 오류 발생: ", e);
            return GlobalResponse.error(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    @PostMapping("/webhook")
    public GlobalResponse handleWebhook(
            @RequestHeader(value = "X-Webhook-Secret", required = false) String secret,
            @RequestBody WebhookEvent event) {
        if (!"123456".equals(secret)) {
            log.error("Unauthorized webhook request. Secret key mismatch or missing");
            return GlobalResponse.error(ErrorCode.ACCESS_DENIED);
        }

        try {
            log.info("Received webhook event: {}", event);
            generalImageWebhookProcessor.processWebhookEvent(event);


            return GlobalResponse.success();
        } catch (Exception e) {
            log.error("Error processing webhook: ", e);
            return GlobalResponse.error(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
    private boolean moderatePrompt(String prompt) {
        String openAiUrl = "https://api.openai.com/v1/chat/completions";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(openAiApiKey);

        try {
            Map<String, Object> body = Map.of(
                    "model", "gpt-4o-mini",
                    "temperature", 0,
                    "messages", List.of(
                            Map.of("role", "system", "content",
                                    "You are a content moderation assistant. Analyze the user's input and determine if it contains inappropriate, offensive, or NSFW content. " +
                                            "Try to censor even inappropriate words that Midjourney can't draw, such as sexual elements, sexual clothing. " +
                                            "Cigarette and Tobacco are not subject to censorship. Respond with 'Content approved'. " +
                                            "If it does, respond with 'Content flagged: [reason]'. If not, respond with 'Content approved.' " +
                                            "Use the following categories to flag content: hate speech, adult content, racism, sexism, or illegal activities."),
                            Map.of("role", "user", "content", prompt)
                    )
            );

            String jsonBody = objectMapper.writeValueAsString(body);
            HttpEntity<String> requestEntity = new HttpEntity<>(jsonBody, headers);

            ResponseEntity<String> responseEntity = restTemplate.exchange(openAiUrl, HttpMethod.POST, requestEntity, String.class);
            String responseBody = responseEntity.getBody();

            JsonNode rootNode = objectMapper.readTree(responseBody);
            String result = rootNode.path("choices").get(0).path("message").path("content").asText().trim();

            log.info("OpenAI 검열 응답: {}", result);

            if ("Content approved.".equalsIgnoreCase(result)) {
                return true;
            } else if (result.startsWith("Content flagged:")) {
                return false;
            } else {
                log.warn("Unexpected response from OpenAI: {}", result);
                return true;
            }

        } catch (Exception e) {
            log.error("OpenAI API 요청 실패: ", e);
            return true;
        }
    }


    private String modifyPrompt(String prompt) {
        String openAiUrl = "https://api.openai.com/v1/chat/completions";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(openAiApiKey);

        try {
            // 메시지 구성
            Map<String, Object> systemMessage = Map.of(
                    "role", "system",
                    "content", "In english, You are an artist. You are going to describe an illustration " +
                            "that meets the user's demand. Don't over-imagine. Use specific wording (ex, light and shadow texture, flat colors, cell shading and ink lines). " +
                            "The style description needs to go first and last in the prompt (ex, retro anime, japanese illustration), or use the director or " +
                            "artist's name related to the style (ex, Ghibli Studio, Hayao Miyazaki, Jeremy Geddes, Junji Ito, Naoko Takeuchi, ...), or specific style " +
                            "(ex: retro anime -> vhs effect, grainy texture, 80s anime, motion blur, realistic -> 4k). If it's animation or character, " +
                            "write simply, in 1~2 sentences. If the user wants a pretty girl, add 'in the style of guweiz'. Don't use korean. " +
                            "If it's realism, describe pose, layout, composition, add 4k."
            );
            Map<String, Object> userMessage = Map.of(
                    "role", "user",
                    "content", prompt
            );

            Map<String, Object> body = Map.of(
                    "model", "gpt-4o-mini",
                    "temperature", 0,
                    "messages", List.of(systemMessage, userMessage)
            );

            String jsonBody = objectMapper.writeValueAsString(body);
            HttpEntity<String> requestEntity = new HttpEntity<>(jsonBody, headers);

            ResponseEntity<String> responseEntity = restTemplate.exchange(openAiUrl, HttpMethod.POST, requestEntity, String.class);
            String responseBody = responseEntity.getBody();

            JsonNode rootNode = objectMapper.readTree(responseBody);
            String result = rootNode.path("choices").get(0).path("message").path("content").asText().trim();

            log.info("OpenAI 프롬프트 개선 응답: {}", result);
            return result;

        } catch (Exception e) {
            log.error("OpenAI API 요청 실패: ", e);
            return prompt;
        }
    }


    @GetMapping("/queue")
    public long getQueueLength() {
        return redisTemplate.opsForList().size("image:queue");
    }


}