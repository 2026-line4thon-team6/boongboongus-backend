package com.boongboongus.backend.domain.appconfig;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.ZoneId;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.public-config")
public record AppConfigResponse(
        @NotBlank String apiVersion,
        @NotBlank String contractVersion,
        @NotBlank String policyVersion,
        @NotNull PolicyStatus policyStatus,
        @NotNull ZoneId timezone,
        @NotNull @Valid Limits limits,
        @NotNull @Valid Features features,
        @NotEmpty List<AuthProvider> authProviders,
        @NotEmpty List<@NotBlank String> moods,
        @NotEmpty List<@NotBlank String> weatherOptions,
        @NotEmpty List<@NotBlank String> genres,
        @Positive int gpsBatchMaxSamples,
        @Positive int gpsLateUploadGraceSeconds,
        @Positive int arrivalRadiusMeters,
        @Positive int arrivalDwellSeconds,
        @Positive int gameSetQuestionCount,
        @NotBlank String defaultMascotName) {

    public enum PolicyStatus { DRAFT, APPROVED }

    public enum AuthProvider { KAKAO }

    public record Limits(
            @Positive int nicknameMaxLength,
            @Positive int driveTitleMaxLength,
            @Positive int cardPhotoMaxCount,
            @Positive int reflectionMaxLength,
            @Positive int radioStoryMaxLength,
            @Positive int recentSearchMaxCount,
            @Positive int recommendedPlaceCount,
            @Positive int restAreaMenuMaxCount,
            @Positive int moodMaxCount,
            @Positive int genreMaxCount,
            @Positive int uploadMaxBytes,
            @NotEmpty List<@NotBlank String> uploadMimeTypes) {
    }

    public record Features(
            @NotNull Boolean coupleRequiredForDrive,
            @NotNull Boolean freeDriveWithoutDestination,
            @NotNull Boolean restAreaDetails,
            @NotNull Boolean musicPlayback) {
    }
}
