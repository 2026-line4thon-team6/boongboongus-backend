package com.boongboongus.backend.domain.notification.service;

import com.boongboongus.backend.domain.device.repository.DevicePushTokenRepository;
import com.boongboongus.backend.domain.notification.repository.NotificationRepository;
import com.boongboongus.backend.domain.push.WebPushGateway;
import com.boongboongus.backend.domain.push.WebPushSubscription;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.json.JsonMapper;

/**
 * 새 인앱 알림을 받는 사람의 웹(PWA) 기기로 보낸다. 커밋된 알림만, 요청 스레드를 막지 않게 따로 보낸다.
 * 발송 실패는 알림 저장에 영향을 주지 않는다 (인앱 알림이 원본).
 */
@Component
class NotificationPushSender {
    private static final Logger log = LoggerFactory.getLogger(NotificationPushSender.class);

    private final WebPushGateway gateway;
    private final DevicePushTokenRepository devices;
    private final NotificationRepository notifications;
    private final JsonMapper json;
    private final Executor executor;

    NotificationPushSender(WebPushGateway gateway, DevicePushTokenRepository devices,
            NotificationRepository notifications, JsonMapper json, @Qualifier("applicationTaskExecutor") Executor executor) {
        this.gateway = gateway;
        this.devices = devices;
        this.notifications = notifications;
        this.json = json;
        this.executor = executor;
    }

    // 트랜잭션 밖에서 저장된 알림도 보낸다 (fallbackExecution)
    @TransactionalEventListener(fallbackExecution = true)
    void onCreated(NotificationCreated event) {
        if (!gateway.enabled()) {
            return;
        }
        executor.execute(() -> deliver(event));
    }

    void deliver(NotificationCreated event) {
        try {
            var targets = devices.findEnabledWebDevices(event.recipientId());
            if (targets.isEmpty()) {
                return;
            }
            byte[] payload = json.writeValueAsBytes(payload(event));
            for (var device : targets) {
                WebPushSubscription subscription;
                try {
                    subscription = WebPushSubscription.parse(device.token(), json);
                } catch (IllegalArgumentException exception) {
                    devices.deleteRegistration(device.deviceId(), device.token());
                    continue;
                }
                if (gateway.send(subscription, payload) == WebPushGateway.Result.GONE) {
                    devices.deleteRegistration(device.deviceId(), device.token());
                }
            }
        } catch (RuntimeException exception) {
            log.warn("Notification push failed: {}", exception.getClass().getSimpleName());
        }
    }

    private Map<String, Object> payload(NotificationCreated event) {
        String nickname = event.senderId() == null ? null
                : notifications.findNickname(event.senderId()).filter(name -> !name.isBlank()).orElse(null);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("notificationId", event.id());
        payload.put("type", event.type().name());
        payload.put("title", nickname == null ? event.title() : title(event, nickname));
        payload.put("body", event.body());
        payload.put("targetType", event.targetType().name());
        payload.put("targetId", event.targetId());
        return payload;
    }

    // 휴대폰 알림 제목은 보낸 사람 닉네임으로 쓴다
    private static String title(NotificationCreated event, String nickname) {
        return switch (event.type()) {
            case PLACE_SHARED -> nickname + "님이 드라이브 장소를 공유했어요";
            case CARD_COMPLETED -> nickname + "님이 드라이브 카드를 완성했어요";
            case REFLECTION_SUBMITTED -> nickname + "님이 드라이브 소감을 남겼어요";
            case COUPLE_CONNECTED -> nickname + "님과 커플이 연결됐어요";
            case COUPLE_DISCONNECTED -> nickname + "님과 커플 연결이 해제됐어요";
        };
    }
}
