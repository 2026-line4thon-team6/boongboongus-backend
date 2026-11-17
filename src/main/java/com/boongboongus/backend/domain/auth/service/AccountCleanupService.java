package com.boongboongus.backend.domain.auth.service;

import com.boongboongus.backend.domain.auth.repository.AccountCleanupRepository;
import com.boongboongus.backend.domain.auth.repository.ServiceSessionRepository;
import com.boongboongus.backend.domain.couple.repository.CoupleInviteRepository;
import com.boongboongus.backend.domain.couple.service.CoupleService;
import com.boongboongus.backend.domain.drive.ActiveDriveLookup;
import com.boongboongus.backend.domain.drive.DrivePreparationService;
import com.boongboongus.backend.domain.media.MediaService;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AccountCleanupService {
    private final AccountCleanupRepository cleanup;
    private final ServiceSessionRepository sessions;
    private final CoupleService couples;
    private final CoupleInviteRepository invites;
    private final ActiveDriveLookup activeDrives;
    private final DrivePreparationService drives;
    private final MediaService media;

    AccountCleanupService(AccountCleanupRepository cleanup, ServiceSessionRepository sessions, CoupleService couples,
            CoupleInviteRepository invites, ActiveDriveLookup activeDrives, DrivePreparationService drives,
            MediaService media) {
        this.cleanup = cleanup;
        this.sessions = sessions;
        this.couples = couples;
        this.invites = invites;
        this.activeDrives = activeDrives;
        this.drives = drives;
        this.media = media;
    }

    @Transactional
    public void deactivateAndClean(String userId) {
        if (!cleanup.lockActiveAccount(userId)) {
            return;
        }
        if (activeDrives.existsForMember(userId)) {
            throw TokenExchangeException.activeDriveExists();
        }
        couples.findCurrent(userId).ifPresent(couple -> couples.disconnect(userId, couple.id(), drives));
        invites.deactivateForOwner(userId);
        sessions.revokeAll(userId, Instant.now());
        media.deleteForWithdrawnOwner(userId);
        cleanup.removePersonalData(userId);
        cleanup.removeRecordedCoordinates(userId);
        cleanup.anonymizeAccount(userId);
    }
}
