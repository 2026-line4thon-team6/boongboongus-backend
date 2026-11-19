package com.boongboongus.backend.domain.notification.service;

import com.boongboongus.backend.domain.notification.dto.NotificationResponse.TargetType;
import com.boongboongus.backend.domain.notification.dto.NotificationResponse.Type;

/**
 * 인앱 알림이 새로 저장됨. 커밋된 뒤 받는 사람의 기기로 푸시를 보낸다.
 */
record NotificationCreated(String id, String senderId, String recipientId, Type type, String title, String body,
        TargetType targetType, String targetId) {
}
