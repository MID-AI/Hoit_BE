package com.ll.demo03.domain.image.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ll.demo03.domain.image.dto.ImageUrlsResponse;
import com.ll.demo03.domain.image.dto.WebhookEvent;
import com.ll.demo03.domain.image.entity.Image;
import com.ll.demo03.domain.image.repository.ImageRepository;
import com.ll.demo03.domain.image.repository.SseEmitterRepository;
import com.ll.demo03.domain.image.service.ImageService;
import com.ll.demo03.domain.member.entity.Member;
import com.ll.demo03.domain.member.repository.MemberRepository;
import com.ll.demo03.global.dto.GlobalResponse;
import com.ll.demo03.global.error.ErrorCode;
import jakarta.persistence.EntityNotFoundException;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/images")
@Slf4j  // 로깅 추가
public class ImageController {

    @Value("${custom.webhook-url}")
    private String webhookUrl;

    @Value("${openai.api-key")
    private String openAiApiKey;

    private final ImageService imageService;
    private final MemberRepository memberRepository;
    private final ImageRepository imageRepository;
    private final SseEmitterRepository sseEmitterRepository;
    private final RestTemplate restTemplate;

    @PostMapping(value = "/create", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("isAuthenticated()")
    public SseEmitter createImage(@RequestBody Map<String, String> data, Authentication authentication) {

        Optional<Member> optionalMember = memberRepository.findByEmail(authentication.getName());
        Member member = optionalMember.orElseThrow(() -> new RuntimeException("Member not found"));

        String prompt = data.get("prompt");

        // 📌 **ChatGPT API를 사용하여 prompt 검열**
        boolean isSafe = moderatePrompt(prompt);
        if (!isSafe) {
            log.warn("Prompt 검열 실패: {}", prompt);
            throw new RuntimeException("Prompt contains inappropriate content");
        }

        // 타임아웃을 10분으로 설정
        SseEmitter emitter = new SseEmitter(600000L); // 10분

        try {

            emitter.send(SseEmitter.event()
                    .name("status")
                    .data("이미지 생성 중..."));

            String response = imageService.createImage(data.get("prompt"), data.get("ratio"), webhookUrl+"/api/images/webhook");
            try {
                ObjectMapper objectMapper = new ObjectMapper();
                JsonNode rootNode = objectMapper.readTree(response);
                String taskId = rootNode.path("data").path("task_id").asText();

                System.out.println("Extracted task_id: " + taskId);

                emitter.send(SseEmitter.event()
                        .name("task_id")
                        .data(taskId));

                Image image = new Image();
                image.setTaskid(taskId);
                imageRepository.save(image);
                sseEmitterRepository.save(taskId, emitter);

                emitter.onTimeout(() -> {
                    log.warn("SSE connection timed out for taskId: {}", taskId);
                    sseEmitterRepository.remove(taskId);
                    emitter.complete();
                });

                emitter.onCompletion(() -> {
                    log.info("SSE connection completed for taskId: {}", taskId);
                    sseEmitterRepository.remove(taskId);
                });

            } catch (Exception e) {
                log.error("JSON 파싱 오류: ",  e);
                emitter.completeWithError(e);
            }
        } catch (Exception e) {
            log.error("이미지 생성 중 오류 발생: ", e);
            emitter.completeWithError(e);
        }
        return emitter;
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

            if (!"completed".equals(event.getData().getStatus())) {
                log.info("Task not yet completed, status: {}", event.getData().getStatus());
                return GlobalResponse.success();
            }

            String taskId = event.getData().getTask_id();
            System.out.println("Received taskId: " + taskId);
            List<String> imageUrls = event.getData().getOutput().getImage_urls();
            System.out.println("Received imageUrls: " + imageUrls);

            if (imageUrls == null || imageUrls.isEmpty()) {
                log.info("No image URLs available yet for taskId: {}", taskId);
                return GlobalResponse.success();
            }

            Image image = imageRepository.findByTaskid(taskId)
                    .orElseThrow(() -> new EntityNotFoundException("Image not found"));

            if (imageUrls != null && imageUrls.size() >= 4) {
                image.setImage_url1(imageUrls.get(0));
                image.setImage_url2(imageUrls.get(1));
                image.setImage_url3(imageUrls.get(2));
                image.setImage_url4(imageUrls.get(3));
                imageRepository.save(image);
            }

            SseEmitter emitter = sseEmitterRepository.get(taskId);
            if (emitter != null) {
                ImageUrlsResponse response = new ImageUrlsResponse(imageUrls);
                emitter.send(SseEmitter.event()
                        .name("result")
                        .data(response));
                emitter.complete();
                sseEmitterRepository.remove(taskId);
            }

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

        String requestBody = String.format(
                "{\n" +
                        "  \"model\": \"gpt-4\",\n" +
                        "  \"messages\": [\n" +
                        "    {\"role\": \"system\", \"content\": \"You are a content moderation assistant. Analyze the user's input and determine if it contains inappropriate, offensive, or NSFW content. " +
                        "Try to censor even inappropriate words that Midjourney can't draw, such as sexual elements, sexual clothing. " +
                        "Cigarette and Tobacco are not subject to censorship. Respond with 'Content approved'. " +
                        "If it does, respond with 'Content flagged: [reason]'. If not, respond with 'Content approved'. " +
                        "Use the following categories to flag content: hate speech, adult content, racism, sexism, or illegal activities.\"},\n" +
                        "    {\"role\": \"user\", \"content\": \"%s\"}\n" +
                        "  ],\n" +
                        "  \"temperature\": 0\n" +
                        "}", prompt);

        HttpEntity<String> requestEntity = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<String> responseEntity = restTemplate.exchange(openAiUrl, HttpMethod.POST, requestEntity, String.class);
            String responseBody = responseEntity.getBody();

            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode rootNode = objectMapper.readTree(responseBody);
            String result = rootNode.path("choices").get(0).path("message").path("content").asText().trim(); // 응답 문자열 가져오기

            log.info("OpenAI 검열 응답: {}", result);

            // 📌 검열 로직 수정
            if (result.equalsIgnoreCase("Content approved")) {
                return true;  // ✅ 정상적인 프롬프트
            } else if (result.startsWith("Content flagged:")) {
                return false; // 🚨 검열 대상
            } else {
                log.warn("Unexpected response from OpenAI: {}", result);
                return false;  // 🚨 예외적인 응답도 검열된 것으로 간주
            }
        } catch (Exception e) {
            log.error("OpenAI API 요청 실패: ", e);
            return true;  // API 오류 시 기본적으로 검열을 우회 (필요하면 `false`로 변경 가능)
        }
    }



//    @GetMapping("/status/{taskId}")
//    public GlobalResponse<String> checkStatus(@PathVariable String taskId) {
//        log.info("Checking status for taskId: {}", taskId);
//        String imageUrl = imageService.checkTaskStatus(taskId);
//        return GlobalResponse.success(imageUrl);
//    }
//
//    @PostMapping("/upscale")
//    public GlobalResponse<String> upscaleImage(@RequestBody Map<String, String> data) {
//        String originTaskId = data.get("originTaskId");
//        int index = Integer.parseInt(data.get("index"));
//
//        log.info("Starting upscale for taskId: {} with index: {}", originTaskId, index);
//
//        String taskId = imageService.upscale(originTaskId, index);
//        String upscaledImageUrl = imageService.checkTaskStatus(taskId);
//
//        return GlobalResponse.success(upscaledImageUrl);
//    }
}