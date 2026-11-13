package com.boongboongus.backend.domain.legal;

import com.boongboongus.backend.domain.legal.LegalDocumentResponse.Type;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class LegalDocumentService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

    private final Map<Type, Map<String, LegalDocumentResponse>> documents = new EnumMap<>(Type.class);

    public LegalDocumentService(LegalDocumentProperties properties) {
        for (LegalDocumentResponse document : properties.documents()) {
            var versions = documents.computeIfAbsent(document.type(), type -> new LinkedHashMap<>());
            if (versions.putIfAbsent(document.version(), document) != null) {
                throw new IllegalArgumentException("Duplicate legal document type and version: "
                        + document.type() + " " + document.version());
            }
        }
    }

    public List<LegalDocumentResponse> listCurrent() {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        return Arrays.stream(Type.values())
                .map(type -> findCurrent(type, today))
                .flatMap(Optional::stream)
                .toList();
    }

    public Optional<LegalDocumentResponse> find(Type type, String version) {
        return version == null
                ? findCurrent(type, LocalDate.now(BUSINESS_ZONE))
                : Optional.ofNullable(documents.getOrDefault(type, Map.of()).get(version));
    }

    private Optional<LegalDocumentResponse> findCurrent(Type type, LocalDate today) {
        return documents.getOrDefault(type, Map.of()).values().stream()
                .filter(document -> !document.effectiveDate().isAfter(today))
                .reduce((current, candidate) -> candidate.effectiveDate().isBefore(current.effectiveDate())
                        ? current : candidate);
    }
}
