package com.boongboongus.backend.domain.drive;

import com.boongboongus.backend.domain.auth.repository.AccountRepository;
import com.boongboongus.backend.domain.legal.LegalDocumentResponse.Type;
import com.boongboongus.backend.domain.legal.LegalDocumentService;
import org.springframework.context.annotation.Fallback;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@Fallback
class AccountLocationConsentLookup implements DriveLocationConsentLookup {
    // Article 3 of this version covers location processing for drive recording.
    private static final String LOCATION_PRIVACY_VERSION = "v1";

    private final AccountRepository accounts;
    private final LegalDocumentService documents;

    AccountLocationConsentLookup(AccountRepository accounts, LegalDocumentService documents) {
        this.accounts = accounts;
        this.documents = documents;
    }

    @Override
    public boolean hasLocationConsent(String userId) {
        var document = documents.find(Type.PRIVACY, LOCATION_PRIVACY_VERSION)
                .orElseThrow(() -> new DriveApiException(HttpStatus.SERVICE_UNAVAILABLE,
                        "SERVICE_UNAVAILABLE", "위치 정보 동의를 확인할 수 없습니다."));
        return accounts.hasConsent(userId, document.type().name(), document.version());
    }
}
