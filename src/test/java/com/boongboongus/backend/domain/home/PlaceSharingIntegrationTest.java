package com.boongboongus.backend.domain.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.boongboongus.backend.domain.drive.CurrentCoupleLookup;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "app.places.share-base-url=https://share.example.test")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PlaceSharingIntegrationTest.Fixtures.class)
class PlaceSharingIntegrationTest {
    private static final String API = "/api/v1";
    private static final String PLACE = "plc_seoul_forest";
    private static final String OWNER = "sharing_owner";
    private static final String PARTNER = "sharing_partner";
    private static final String COUPLE = "sharing_couple";
    private static final String FAVORITES = API + "/me/favorite-places";
    private static final String SEND = API + "/places/" + PLACE + "/send-to-partner";

    @Autowired MockMvc mvc;
    @Autowired AtomicInteger deliveries;

    @Test
    void favoritesArePrivateIdempotentAndReflectedInDetail() throws Exception {
        String owner = UUID.randomUUID().toString();
        mvc.perform(get(FAVORITES)).andExpect(status().isUnauthorized());
        mvc.perform(put(FAVORITES + "/" + PLACE).principal(() -> owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.accepted").value(true));
        mvc.perform(put(FAVORITES + "/" + PLACE).principal(() -> owner)).andExpect(status().isOk());
        mvc.perform(get(FAVORITES).principal(() -> owner).param("category", "NATURE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(PLACE))
                .andExpect(jsonPath("$.data.items[0].isFavorite").value(true))
                .andExpect(jsonPath("$.data.hasMore").value(false));
        mvc.perform(get(FAVORITES).principal(() -> owner).param("category", "SEA"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(0));
        mvc.perform(get(FAVORITES).principal(() -> PARTNER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(0));
        mvc.perform(get(API + "/places/" + PLACE).principal(() -> owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.place.isFavorite").value(true));
        mvc.perform(delete(FAVORITES + "/" + PLACE).principal(() -> PARTNER)).andExpect(status().isNoContent());
        mvc.perform(get(FAVORITES).principal(() -> owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1));
        mvc.perform(delete(FAVORITES + "/" + PLACE).principal(() -> owner)).andExpect(status().isNoContent());
        mvc.perform(delete(FAVORITES + "/" + PLACE).principal(() -> owner)).andExpect(status().isNoContent());
        mvc.perform(get(API + "/places/" + PLACE).principal(() -> owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.place.isFavorite").value(false));
        mvc.perform(put(FAVORITES + "/plc_k_external").principal(() -> owner)).andExpect(status().isNotFound());
    }

    @Test
    void publicLookupReturnsOnlyAllowedFieldsAndLinkUsesConfiguredHost() throws Exception {
        mvc.perform(get(API + "/places/" + PLACE + "/share")).andExpect(status().isUnauthorized());
        mvc.perform(get(API + "/places/" + PLACE + "/share").principal(() -> OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").value("https://share.example.test/places/" + PLACE))
                .andExpect(jsonPath("$.data.access").value("PUBLIC_PLACE"))
                .andExpect(jsonPath("$.data.expiresAt").value((Object) null));
        mvc.perform(get(API + "/public/places/" + PLACE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("서울숲"))
                .andExpect(jsonPath("$.data.length()").value(6))
                .andExpect(jsonPath("$.data.coordinate.latitude").isNumber())
                .andExpect(jsonPath("$.data.isFavorite").doesNotExist())
                .andExpect(jsonPath("$.data.recipientUserId").doesNotExist());
        mvc.perform(get(API + "/public/places/unknown")).andExpect(status().isNotFound());
    }

    @Test
    void currentPartnerIsResolvedOnServerAndConcurrentRetriesCreateOneNotification() throws Exception {
        String key = UUID.randomUUID().toString();
        int before = deliveries.get();
        mvc.perform(post(SEND).principal(() -> OWNER).header("X-Couple-Id", "stale")
                .header("Idempotency-Key", key)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COUPLE_CONTEXT_CHANGED"));
        mvc.perform(post(SEND).principal(() -> "single").header("X-Couple-Id", COUPLE)
                .header("Idempotency-Key", key)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COUPLE_REQUIRED"));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> send(key));
            var second = executor.submit(() -> send(key));
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
        }
        assertThat(deliveries.get()).isEqualTo(before + 1);
        mvc.perform(post(API + "/places/different/send-to-partner").principal(() -> OWNER)
                .header("X-Couple-Id", COUPLE).header("Idempotency-Key", key))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    private String send(String key) throws Exception {
        var response = mvc.perform(post(SEND).principal(() -> OWNER).header("X-Couple-Id", COUPLE)
                .header("Idempotency-Key", key)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.recipientUserId").value(PARTNER))
                .andReturn().getResponse();
        return tools.jackson.databind.json.JsonMapper.shared().readTree(response.getContentAsByteArray())
                .at("/data").toString();
    }

    @TestConfiguration
    static class Fixtures {
        @Bean
        CurrentCoupleLookup currentCoupleLookup() {
            return user -> OWNER.equals(user) || PARTNER.equals(user)
                    ? Optional.of(new CurrentCoupleLookup.Couple(COUPLE, OWNER, PARTNER)) : Optional.empty();
        }

        @Bean
        AtomicInteger deliveries() {
            return new AtomicInteger();
        }

        @Bean
        PlaceShareNotification notifications(AtomicInteger deliveries) {
            return (couple, sender, recipient, place) -> {
                assertThat(couple).isEqualTo(COUPLE);
                assertThat(sender).isEqualTo(OWNER);
                assertThat(recipient).isEqualTo(PARTNER);
                assertThat(place).isEqualTo(PLACE);
                return new PlaceShareNotification.Receipt("ntf_" + deliveries.incrementAndGet(), Instant.now());
            };
        }
    }
}
