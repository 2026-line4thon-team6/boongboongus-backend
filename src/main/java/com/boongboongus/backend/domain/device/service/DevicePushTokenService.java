package com.boongboongus.backend.domain.device.service;

import com.boongboongus.backend.domain.device.dto.PushTokenRequest;
import com.boongboongus.backend.domain.device.dto.PushTokenRequest.Platform;
import com.boongboongus.backend.domain.device.repository.DevicePushTokenRepository;
import com.boongboongus.backend.domain.push.WebPushSubscription;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class DevicePushTokenService {
    private final DevicePushTokenRepository devices;
    private final JsonMapper json;

    DevicePushTokenService(DevicePushTokenRepository devices, JsonMapper json) {
        this.devices = devices;
        this.json = json;
    }

    public void register(String deviceId, String userId, PushTokenRequest request) {
        // 웹(PWA)은 브라우저 PushSubscription JSON을 token으로 받는다. 발송할 수 없는 형식은 저장하지 않는다
        if (request.platform() == Platform.WEB) {
            try {
                WebPushSubscription.parse(request.token(), json);
            } catch (IllegalArgumentException exception) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            }
        }
        if (!devices.register(deviceId, userId, request.token(), request.platform().name(), request.enabled())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    public void unregister(String deviceId, String userId) {
        devices.deleteOwnedRegistration(deviceId, userId);
    }
}
