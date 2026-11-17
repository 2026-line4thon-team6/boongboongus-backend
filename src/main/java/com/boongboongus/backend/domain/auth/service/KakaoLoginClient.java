package com.boongboongus.backend.domain.auth.service;

import com.boongboongus.backend.domain.auth.config.KakaoLoginProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
class KakaoLoginClient {
    private final KakaoLoginProperties kakao;
    private final RestClient http;

    @Autowired
    KakaoLoginClient(KakaoLoginProperties kakao) {
        this(kakao, createHttpClient());
    }

    KakaoLoginClient(KakaoLoginProperties kakao, RestClient http) {
        this.kakao = kakao;
        this.http = http;
    }

    private static RestClient createHttpClient() {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(4));
        return RestClient.builder().requestFactory(factory).build();
    }

    long authenticate(String code, String redirectUri) {
        if (!StringUtils.hasText(kakao.restApiKey())) {
            throw TokenExchangeException.unavailable();
        }
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", kakao.restApiKey());
        form.add("redirect_uri", redirectUri);
        form.add("code", code);
        if (StringUtils.hasText(kakao.clientSecret())) {
            form.add("client_secret", kakao.clientSecret());
        }

        try {
            var token = http.post().uri("https://kauth.kakao.com/oauth/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                    .retrieve().body(KakaoToken.class);
            if (token == null || !StringUtils.hasText(token.accessToken())) {
                throw TokenExchangeException.unavailable();
            }

            var user = http.get().uri("https://kapi.kakao.com/v2/user/me")
                    .headers(headers -> headers.setBearerAuth(token.accessToken()))
                    .retrieve().body(KakaoUser.class);
            if (user == null || user.id() == null) {
                throw TokenExchangeException.unavailable();
            }
            return user.id();
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == 400 || status == 401 || status == 403) {
                throw TokenExchangeException.verificationFailed();
            }
            throw TokenExchangeException.unavailable();
        } catch (RestClientException exception) {
            throw TokenExchangeException.unavailable();
        }
    }

    void unlink(long kakaoId) {
        if (!StringUtils.hasText(kakao.adminKey())) {
            throw TokenExchangeException.providerUnlinkFailed();
        }

        var form = new LinkedMultiValueMap<String, String>();
        form.add("target_id_type", "user_id");
        form.add("target_id", Long.toString(kakaoId));

        try {
            var response = http.post().uri("https://kapi.kakao.com/v1/user/unlink")
                    .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + kakao.adminKey())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                    .retrieve().toEntity(KakaoUser.class);
            var user = response.getBody();
            if (!response.getStatusCode().is2xxSuccessful() || user == null || user.id() == null
                    || user.id() != kakaoId) {
                throw TokenExchangeException.providerUnlinkFailed();
            }
        } catch (RestClientException exception) {
            throw TokenExchangeException.providerUnlinkFailed();
        }
    }

    private record KakaoToken(@JsonProperty("access_token") String accessToken) {}
    private record KakaoUser(Long id) {}
}
