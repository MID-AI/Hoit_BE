package com.ll.demo03.domain.oauth;

import com.ll.demo03.domain.oauth.token.TokenProvider;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

import com.ll.demo03.domain.member.repository.MemberRepository;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class OauthController {

    private final TokenProvider tokenProvider;
    private final MemberRepository memberRepository;


    @GetMapping("/login/google")
    public ResponseEntity<Map<String, String>> getLoginUrl(
            @RequestParam(required = false) String redirectUrl
    ) {
        String encodedRedirectUrl = redirectUrl != null ?
                java.net.URLEncoder.encode(redirectUrl, java.nio.charset.StandardCharsets.UTF_8) : "";

        String loginUrl = "/oauth2/authorization/google";
        if (!encodedRedirectUrl.isEmpty()) {
            loginUrl += "?state=" + encodedRedirectUrl;
        }

        Map<String, String> response = new HashMap<>();
        response.put("url", loginUrl);

        return ResponseEntity.ok(response);
    }

    @PostMapping("/login/process")
    public ResponseEntity<Map<String, Object>> processLogin(
            @RequestParam String redirectUrl,
            HttpServletResponse response
    ) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated() ||
                "anonymousUser".equals(authentication.getPrincipal())) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Not authenticated");
            return ResponseEntity.status(401).body(errorResponse);
        }

        String accessToken = tokenProvider.generateAccessToken(authentication);
        String refreshToken = tokenProvider.generateRefreshToken(authentication, accessToken);

        Cookie accessCookie = new Cookie("_hoauth", accessToken);
        accessCookie.setHttpOnly(true);
        accessCookie.setSecure(true);
        accessCookie.setPath("/");
        accessCookie.setMaxAge(3600);

        Cookie refreshCookie = new Cookie("_hrauth", refreshToken);
        refreshCookie.setHttpOnly(true);
        refreshCookie.setSecure(true);
        refreshCookie.setPath("/");
        refreshCookie.setMaxAge(3600); // 1시간

        response.addCookie(accessCookie);
        response.addCookie(refreshCookie);

        // 응답 데이터 구성
        Map<String, Object> responseData = new HashMap<>();
        responseData.put("success", true);
        responseData.put("redirectUrl", redirectUrl);

        return ResponseEntity.ok(responseData);
    }

    /**
     * 쿠키 기반 로그인 상태 확인
     */
    @GetMapping("/login/status")
    public ResponseEntity<Map<String, Object>> checkLoginStatus(
            @CookieValue(name = "_hoauth", required = false) String accessToken
    ) {
        Map<String, Object> response = new HashMap<>();
        boolean isLoggedIn = accessToken != null;
        response.put("isLoggedIn", isLoggedIn);

        return ResponseEntity.ok(response);
    }

    /**
     * 로그아웃 처리
     */
    @GetMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpServletResponse response) {
        // 쿠키 제거
        Cookie accessCookie = new Cookie("_hoauth", null);
        accessCookie.setHttpOnly(true);
        accessCookie.setSecure(true);
        accessCookie.setPath("/");
        accessCookie.setMaxAge(0);

        Cookie refreshCookie = new Cookie("_hrauth", null);
        refreshCookie.setHttpOnly(true);
        refreshCookie.setSecure(true);
        refreshCookie.setPath("/");
        refreshCookie.setMaxAge(0);

        response.addCookie(accessCookie);
        response.addCookie(refreshCookie);

        // 시큐리티 컨텍스트 클리어
        SecurityContextHolder.clearContext();

        Map<String, Object> responseData = new HashMap<>();
        responseData.put("success", true);

        return ResponseEntity.ok(responseData);
    }
}