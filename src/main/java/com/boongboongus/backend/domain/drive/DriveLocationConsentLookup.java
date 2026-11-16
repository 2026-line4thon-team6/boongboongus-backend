package com.boongboongus.backend.domain.drive;

/** Reads the recorded location consent for the authenticated GPS recorder. */
public interface DriveLocationConsentLookup {
    boolean hasLocationConsent(String userId);
}
