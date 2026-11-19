package com.boongboongus.backend.domain.push;

/**
 * 푸시 서비스로 암호화한 메시지 한 건을 보낸다.
 */
public interface WebPushGateway {
    boolean enabled();

    /**
     * @return 푸시 서비스 응답. GONE이면 구독이 만료돼 기기 등록을 지운다
     */
    Result send(WebPushSubscription subscription, byte[] payload);

    enum Result { SENT, GONE, FAILED }
}
