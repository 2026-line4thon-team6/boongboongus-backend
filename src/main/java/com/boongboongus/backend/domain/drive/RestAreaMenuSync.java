package com.boongboongus.backend.domain.drive;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@Component
class RestAreaMenuSync {
    private static final Logger LOG = LoggerFactory.getLogger(RestAreaMenuSync.class);
    private static final int PAGE_SIZE = 99;
    private static final int MAX_PAGES = 150;
    private static final int MAX_MENUS = 4;
    private static final Map<String, String> ROAD_ALIASES = Map.of(
            "통영대전선", "대전통영선", "통영대전선,중부선", "대전통영선",
            "호남선의 지선", "호남지선", "중부내륙선의 지선", "중부내륙지선",
            "새만금포항선(대구~포항)", "대구포항선", "서산영덕선", "당진영덕선",
            "완주장수선", "익산장수선", "남해선(영암순천)", "남해선");
    private static final Map<String, String> CONFIRMED_SOURCE_IDS = Map.of(
            "000487", "jyd:52:양평(광주):상행", "000488", "jyd:52:양평(원주):하행",
            "000491", "jyd:52:경기광주(광주):상행", "000492", "jyd:52:경기광주(원주):하행",
            "000609", "ex:65:영덕(영덕):", "000610", "ex:65:포항(포항):",
            "000613", "ex:20:김제(전주):", "000614", "ex:20:김제(새만금):",
            "000601", "ex:14:밀양영남루(함양):상행");

    private final RestAreaCatalog catalog;
    private final TransactionTemplate transactions;
    private final TaskExecutor executor;
    private final RestClient http;
    private final String apiKey;
    private final AtomicBoolean running = new AtomicBoolean();

    RestAreaMenuSync(RestAreaCatalog catalog, TransactionTemplate transactions,
            @Qualifier("applicationTaskExecutor") TaskExecutor executor,
            @Value("${app.rest-area-menus.base-url}") String baseUrl,
            @Value("${app.rest-area-menus.api-key}") String apiKey) {
        this.catalog = catalog;
        this.transactions = transactions;
        this.executor = executor;
        this.apiKey = apiKey.strip();
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Scheduled(fixedDelay = 86_400_000, initialDelay = 10_000)
    void schedule() {
        if (apiKey.isEmpty() || "test".equalsIgnoreCase(apiKey) || !running.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    LOG.info("Rest area menus refreshed: {} facilities", refresh());
                } catch (RuntimeException exception) {
                    LOG.warn("Rest area menu refresh failed ({}); previous menus retained",
                            exception.getClass().getSimpleName());
                } finally {
                    running.set(false);
                }
            });
        } catch (RuntimeException exception) {
            running.set(false);
            LOG.warn("Could not schedule rest area menu refresh ({})", exception.getClass().getSimpleName());
        }
    }

    int refresh() {
        List<Food> foods = fetch();
        var byFacility = foods.stream().collect(Collectors.groupingBy(Food::facility));
        var byCode = foods.stream().collect(Collectors.groupingBy(Food::stdRestCd));
        Map<String, List<String>> updates = new LinkedHashMap<>();
        var areas = catalog.menuFacilities();
        var facilityCounts = areas.stream().collect(Collectors.groupingBy(
                area -> Facility.of(area.sourceName(), area.highway()), Collectors.counting()));
        var codesBySource = CONFIRMED_SOURCE_IDS.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
        for (var area : areas) {
            String confirmedCode = codesBySource.get(area.sourceId());
            var facility = Facility.of(area.sourceName(), area.highway());
            var candidates = confirmedCode != null ? byCode.getOrDefault(confirmedCode, List.of())
                    : facilityCounts.get(facility) == 1
                            ? byFacility.getOrDefault(facility, List.of()) : List.<Food>of();
            if (candidates.stream().map(Food::stdRestCd).distinct().count() != 1) {
                continue;
            }
            var names = candidates.stream()
                    .sorted(Comparator.comparingInt(Food::priority))
                    .map(Food::foodNm)
                    .filter(name -> name != null && !name.isBlank())
                    .map(String::strip)
                    .filter(name -> name.length() <= 120)
                    .distinct()
                    .limit(MAX_MENUS)
                    .toList();
            if (!names.isEmpty()) {
                updates.put(area.id(), names);
            }
        }
        if (updates.isEmpty()) {
            throw new IllegalStateException("No rest area menus matched");
        }
        transactions.executeWithoutResult(status -> updates.forEach(catalog::replaceMenus));
        return updates.size();
    }

    private List<Food> fetch() {
        var foods = new ArrayList<Food>();
        int pages = 1;
        int total = 0;
        for (int page = 1; page <= pages; page++) {
            int requestedPage = page;
            Page result = http.get().uri(builder -> builder.path("/openapi/restinfo/restBestfoodList")
                            .queryParam("key", "{key}").queryParam("type", "json")
                            .queryParam("numOfRows", PAGE_SIZE).queryParam("pageNo", requestedPage).build(apiKey))
                    .retrieve().body(Page.class);
            if (result == null || !"SUCCESS".equals(result.code()) || result.pageNo() != page
                    || result.count() < 1 || result.pageSize() < 1 || result.pageSize() > MAX_PAGES
                    || result.list() == null || result.list().isEmpty() || result.list().size() > PAGE_SIZE) {
                throw new IllegalStateException("Invalid rest area menu page");
            }
            if (page == 1) {
                pages = result.pageSize();
                total = result.count();
            } else if (result.pageSize() != pages || result.count() != total) {
                throw new IllegalStateException("Inconsistent rest area menu pages");
            }
            if (result.list().stream().anyMatch(food -> food == null
                    || food.stdRestCd() == null || food.stdRestCd().isBlank()
                    || food.stdRestNm() == null || food.stdRestNm().isBlank())) {
                throw new IllegalStateException("Invalid rest area menu row");
            }
            foods.addAll(result.list());
        }
        if (foods.size() != total) {
            throw new IllegalStateException("Incomplete rest area menu list");
        }
        return foods;
    }

    private record Facility(String name, String highway) {
        static Facility of(String name, String highway) {
            return new Facility(name.replace("휴게소", "").replace("방향", "").replaceAll("\\s+", ""),
                    highway == null ? "" : highway.replaceAll("\\s+", ""));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Page(String code, int count, int pageNo, int pageSize, List<Food> list) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Food(String stdRestCd, String stdRestNm, String routeNm, String foodNm,
            String bestfoodyn, String recommendyn) {
        Facility facility() {
            return Facility.of(stdRestNm, routeNm == null ? null : ROAD_ALIASES.getOrDefault(routeNm, routeNm));
        }

        int priority() {
            return "Y".equals(bestfoodyn) ? 0 : "Y".equals(recommendyn) ? 1 : 2;
        }
    }
}
