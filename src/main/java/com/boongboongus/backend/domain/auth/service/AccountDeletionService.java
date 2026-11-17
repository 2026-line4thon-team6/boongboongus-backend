package com.boongboongus.backend.domain.auth.service;

import com.boongboongus.backend.domain.auth.repository.AccountCleanupRepository;
import com.boongboongus.backend.domain.auth.repository.AccountRepository;
import com.boongboongus.backend.domain.auth.repository.ReauthProofRepository;
import com.boongboongus.backend.domain.couple.repository.CoupleRepository;
import com.boongboongus.backend.domain.couple.service.CoupleService;
import com.boongboongus.backend.domain.drive.ActiveDriveLookup;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountDeletionService {
    private final ReauthProofRepository proofs;
    private final ActiveDriveLookup drives;
    private final AccountRepository accounts;
    private final AccountCleanupRepository accountLocks;
    private final CoupleService couples;
    private final CoupleRepository memberships;
    private final KakaoLoginClient kakao;
    private final AccountCleanupService cleanup;

    AccountDeletionService(ReauthProofRepository proofs, ActiveDriveLookup drives, AccountRepository accounts,
            AccountCleanupRepository accountLocks, CoupleService couples, CoupleRepository memberships,
            KakaoLoginClient kakao, AccountCleanupService cleanup) {
        this.proofs = proofs;
        this.drives = drives;
        this.accounts = accounts;
        this.accountLocks = accountLocks;
        this.couples = couples;
        this.memberships = memberships;
        this.kakao = kakao;
        this.cleanup = cleanup;
    }

    @Transactional
    public void delete(String userId, String proof) {
        if (proof == null || proof.isBlank() || !accountLocks.lockActiveAccount(userId)) {
            throw TokenExchangeException.reauthRequired();
        }
        // Drive preparation uses the same membership lock.
        couples.findCurrent(userId).ifPresent(couple -> memberships.lockMembership(couple.id(), userId));
        String hash = ServiceTokens.refreshHash(proof);
        if (!proofs.isValidForDeletion(hash, userId, Instant.now())) {
            throw TokenExchangeException.reauthRequired();
        }
        if (drives.existsForMember(userId)) {
            throw TokenExchangeException.activeDriveExists();
        }
        long kakaoId = accounts.findKakaoId(userId).orElseThrow(TokenExchangeException::providerUnlinkFailed);
        kakao.unlink(kakaoId);
        cleanup.deactivateAndClean(userId);
        proofs.consume(hash, userId, Instant.now());
    }
}
