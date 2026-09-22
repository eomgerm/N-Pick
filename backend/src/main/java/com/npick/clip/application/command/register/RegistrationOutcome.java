package com.npick.clip.application.command.register;

/** Tells a new clip apart from an existing one the deduplication returned, so the caller can say why (#283). */
public enum RegistrationOutcome {
    /** This request created the clip. A resend of that same request replays this verdict. */
    CREATED,
    /** The same video file was already registered by this actor; that clip is returned. */
    DUPLICATE_OWN,
    /** The same video file was already registered by someone else; that clip is returned. */
    DUPLICATE_OTHER
}
