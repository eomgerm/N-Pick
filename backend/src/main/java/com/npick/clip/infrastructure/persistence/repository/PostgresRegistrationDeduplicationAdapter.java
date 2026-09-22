package com.npick.clip.infrastructure.persistence.repository;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import javax.sql.DataSource;

import org.springframework.stereotype.Component;

import com.npick.clip.application.command.register.RegisterClipResult;
import com.npick.clip.application.command.register.RegistrationOutcome;
import com.npick.clip.application.error.ClipRuntimeErrorCode;
import com.npick.clip.application.error.RegistrationDeduplicationErrorCode;
import com.npick.clip.application.port.RegistrationDeduplicationPort;
import com.npick.common.error.BusinessException;
import com.npick.common.error.CommonErrorCode;

/** Session locks span the separate #34 transaction; journal writes commit before file creation. */
@Component
public final class PostgresRegistrationDeduplicationAdapter implements RegistrationDeduplicationPort {
    private final DataSource dataSource;

    public PostgresRegistrationDeduplicationAdapter(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public RegisterClipResult register(
            String key, long actor, String content, RequestData request, Supplier<RegisterClipResult> create) {
        if (key == null
                || key.isBlank()
                || key.length() > 128
                || actor <= 0
                || content == null
                || !content.matches("[0-9a-f]{64}")
                || request == null) {
            throw new BusinessException(CommonErrorCode.BAD_REQUEST);
        }
        String keyHash = digest(key);
        String fingerprint = digest(
                "v1",
                content,
                request.sourceType(),
                request.title(),
                request.broadcastDate(),
                request.filmedDate(),
                request.scriptText(),
                request.subtitleHash(),
                request.rightsConfirmed(),
                request.externalProcessingConfirmed());
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            List<Long> attemptedLocks = new ArrayList<>(2);
            Throwable operationFailure = null;
            try {
                lock(connection, "registration-key:" + actor + ":" + keyHash, attemptedLocks);
                lock(connection, "registration-content:" + content, attemptedLocks);
                return locked(connection, actor, keyHash, content, fingerprint, create);
            } catch (SQLException | RuntimeException | Error failure) {
                operationFailure = failure;
                throw failure;
            } finally {
                // Include acquisition attempts whose acknowledgement may have been lost.
                // Release only our locks, never unrelated locks on the session.
                try {
                    for (int i = attemptedLocks.size() - 1; i >= 0; i--) {
                        try (var statement = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                            statement.setLong(1, attemptedLocks.get(i));
                            statement.execute();
                        }
                    }
                } catch (SQLException failure) {
                    if (operationFailure != null) failure.addSuppressed(operationFailure);
                    try {
                        connection.abort(Runnable::run);
                    } catch (SQLException abortFailure) {
                        failure.addSuppressed(abortFailure);
                    }
                    throw failure;
                }
            }
        } catch (SQLException failure) {
            throw new BusinessException(ClipRuntimeErrorCode.REGISTRATION_OUTCOME_UNKNOWN, failure);
        }
    }

    private RegisterClipResult locked(
            Connection db,
            long actor,
            String key,
            String content,
            String fingerprint,
            Supplier<RegisterClipResult> create)
            throws SQLException {
        String previousState = null;
        try (var query =
                db.prepareStatement("SELECT * FROM npick.registration_request WHERE actor_id=? AND key_hash=?")) {
            query.setLong(1, actor);
            query.setString(2, key);
            try (var rows = query.executeQuery()) {
                if (rows.next()) {
                    if (!fingerprint.equals(rows.getString("request_hash"))) {
                        throw new BusinessException(RegistrationDeduplicationErrorCode.KEY_CONFLICT);
                    }
                    previousState = rows.getString("state");
                    if ("succeeded".equals(previousState)) {
                        long clipId = rows.getLong("clip_id");
                        Long owner = ownerOf(db, clipId);
                        if (owner == null) {
                            throw new BusinessException(RegistrationDeduplicationErrorCode.RESULT_DELETED);
                        }
                        // Replay the recorded verdict so a resend repeats the same notice. A row carries no
                        // verdict when the recovery could not settle one — the registrant's row resolved before
                        // any request reported creating this clip, which is how a creation that lost its
                        // acknowledgement stays a creation here — or when it was confirmed before this column
                        // existed. Both fall back to the registrant of the matched clip, so this path is the
                        // decision for such rows and not a legacy shim.
                        String recorded = rows.getString("outcome");
                        var result = new RegisterClipResult(
                                clipId,
                                rows.getLong("pipeline_run_id"),
                                rows.getString("result_status"),
                                recorded != null
                                        ? RegistrationOutcome.valueOf(recorded.toUpperCase(Locale.ROOT))
                                        : owner.longValue() == actor
                                                ? RegistrationOutcome.CREATED
                                                : RegistrationOutcome.DUPLICATE_OTHER);
                        recoverObservedResult(db, content, result);
                        return result;
                    }
                }
            }
        }
        RegisterClipResult existing = findContent(db, content, actor);
        if (existing != null) {
            // This attempt may be the one that created the clip and only lost the acknowledgement; that is a
            // confirmation of its own registration, not a duplicate. A request that finished creating records
            // 'created', so its absence leaves this unresolved attempt as the creator. Rows confirmed before
            // the column exists record nothing, which reads as a creation — the behaviour before #283.
            boolean ownCreation = ("processing".equals(previousState) || "unknown".equals(previousState))
                    && existing.outcome() == RegistrationOutcome.DUPLICATE_OWN
                    && !creatorRecorded(db, content);
            if (ownCreation) {
                existing = new RegisterClipResult(
                        existing.clipId(), existing.pipelineRunId(), existing.status(), RegistrationOutcome.CREATED);
            }
            reserve(db, actor, key, content, fingerprint);
            // Complete every unresolved alias atomically, not just the current key.
            if (recoverObservedResult(db, content, existing) == 0) {
                throw new BusinessException(ClipRuntimeErrorCode.REGISTRATION_OUTCOME_UNKNOWN);
            }
            // That recovery writes a duplicate verdict for every alias it resolves; restore this one.
            if (ownCreation) complete(db, actor, key, existing);
            return existing;
        }
        // A crashed process or lost commit acknowledgement must never trigger another write.
        if ("processing".equals(previousState) || "unknown".equals(previousState) || unresolved(db, content)) {
            throw new BusinessException(ClipRuntimeErrorCode.REGISTRATION_OUTCOME_UNKNOWN);
        }
        reserve(db, actor, key, content, fingerprint);
        RegisterClipResult result;
        try {
            result = create.get();
        } catch (RuntimeException | Error failure) {
            boolean knownFailure = failure instanceof BusinessException business
                    && business.errorCode() != ClipRuntimeErrorCode.REGISTRATION_OUTCOME_UNKNOWN;
            try (var update = db.prepareStatement(
                    "UPDATE npick.registration_request SET state=?, updated_at=now() WHERE actor_id=? AND key_hash=?")) {
                update.setString(1, knownFailure ? "failed" : "unknown");
                update.setLong(2, actor);
                update.setString(3, key);
                update.executeUpdate();
            } catch (SQLException journalFailure) {
                var unknown = new BusinessException(ClipRuntimeErrorCode.REGISTRATION_OUTCOME_UNKNOWN, failure);
                unknown.addSuppressed(journalFailure);
                throw unknown;
            }
            if (!knownFailure) throw new BusinessException(ClipRuntimeErrorCode.REGISTRATION_OUTCOME_UNKNOWN, failure);
            throw failure;
        }
        complete(db, actor, key, result);
        return result;
    }

    private static void reserve(Connection db, long actor, String key, String content, String fingerprint)
            throws SQLException {
        try (var update = db.prepareStatement("""
                INSERT INTO npick.registration_request(actor_id,key_hash,request_hash,content_hash,state)
                VALUES (?,?,?,?,'processing') ON CONFLICT(actor_id,key_hash)
                DO UPDATE SET state='processing',updated_at=now()
                """)) {
            update.setLong(1, actor);
            update.setString(2, key);
            update.setString(3, fingerprint);
            update.setString(4, content);
            update.executeUpdate();
        }
    }

    private static void complete(Connection db, long actor, String key, RegisterClipResult result) throws SQLException {
        try (var update = db.prepareStatement("""
                UPDATE npick.registration_request
                SET state='succeeded',clip_id=?,pipeline_run_id=?,result_status=?,outcome=?,updated_at=now()
                WHERE actor_id=? AND key_hash=?
                """)) {
            update.setLong(1, result.clipId());
            update.setLong(2, result.pipelineRunId());
            update.setString(3, result.status());
            update.setString(4, result.outcome().name().toLowerCase(Locale.ROOT));
            update.setLong(5, actor);
            update.setString(6, key);
            update.executeUpdate();
        }
    }

    private static RegisterClipResult findContent(Connection db, String content, long actor) throws SQLException {
        // Replay the initial registration response, not the mutable pipeline execution status.
        // The registrant decides only how the duplicate is worded; it never narrows the match itself.
        try (var query = db.prepareStatement("""
                SELECT c.clip_id,r.pipeline_run_id,c.registered_by_id
                FROM npick.clip c JOIN npick.pipeline_run r ON r.clip_id=c.clip_id
                WHERE c.content_hash=? AND c.deleted_at IS NULL AND r.processing_no=1
                """)) {
            query.setString(1, content);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) return null;
                return new RegisterClipResult(
                        rows.getLong(1),
                        rows.getLong(2),
                        "queued",
                        rows.getLong(3) == actor
                                ? RegistrationOutcome.DUPLICATE_OWN
                                : RegistrationOutcome.DUPLICATE_OTHER);
            }
        }
    }

    /**
     * The caller holds the content lock and has observed this committed result. Reconcile only matching content backed
     * by the exact clip and its first run; never reset unknown to retryable. Keep the association if a concurrent soft
     * deletion follows the observation.
     */
    private static int recoverObservedResult(Connection db, String content, RegisterClipResult result)
            throws SQLException {
        try (var update = db.prepareStatement("""
                UPDATE npick.registration_request j SET state='succeeded',
                    clip_id=c.clip_id,pipeline_run_id=r.pipeline_run_id,result_status=?,updated_at=now(),
                    -- Verdict per alias, since one recovery resolves rows of different actors. An alias of the
                    -- registrant is only a duplicate once some request has reported creating this clip; until
                    -- then it may be the creation that lost its acknowledgement, so leave the verdict open and
                    -- let the replay decide. This statement never writes 'created', so the lookup is stable.
                    outcome=CASE
                        WHEN j.actor_id<>c.registered_by_id THEN 'duplicate_other'
                        WHEN EXISTS (SELECT 1 FROM npick.registration_request k
                            WHERE k.content_hash=c.content_hash AND k.outcome='created') THEN 'duplicate_own'
                        END
                FROM npick.clip c JOIN npick.pipeline_run r ON r.clip_id=c.clip_id AND r.processing_no=1
                WHERE c.clip_id=? AND r.pipeline_run_id=? AND c.content_hash=?
                    AND j.content_hash=c.content_hash AND j.state IN ('processing','unknown')
                """)) {
            update.setString(1, result.status());
            update.setLong(2, result.clipId());
            update.setLong(3, result.pipelineRunId());
            update.setString(4, content);
            return update.executeUpdate();
        }
    }

    /** Registrant of a clip that is still alive, or null when it is gone. */
    private static Long ownerOf(Connection db, long clipId) throws SQLException {
        try (var query =
                db.prepareStatement("SELECT registered_by_id FROM npick.clip WHERE clip_id=? AND deleted_at IS NULL")) {
            query.setLong(1, clipId);
            try (var rows = query.executeQuery()) {
                return rows.next() ? rows.getLong(1) : null;
            }
        }
    }

    /** Whether some request already reported creating this content, which no longer leaves an unresolved creator. */
    private static boolean creatorRecorded(Connection db, String content) throws SQLException {
        try (var query = db.prepareStatement(
                "SELECT 1 FROM npick.registration_request WHERE content_hash=? AND outcome='created' LIMIT 1")) {
            query.setString(1, content);
            try (var rows = query.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static boolean unresolved(Connection db, String content) throws SQLException {
        try (var query = db.prepareStatement(
                "SELECT 1 FROM npick.registration_request WHERE content_hash=? AND state IN ('processing','unknown') LIMIT 1")) {
            query.setString(1, content);
            try (var rows = query.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static void lock(Connection db, String name, List<Long> attemptedLocks) throws SQLException {
        long id = ByteBuffer.wrap(HexFormat.of().parseHex(digest(name))).getLong();
        try (var query = db.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            query.setLong(1, id);
            attemptedLocks.add(id);
            try (var rows = query.executeQuery()) {
                rows.next();
                if (!rows.getBoolean(1)) {
                    attemptedLocks.removeLast();
                    throw new BusinessException(RegistrationDeduplicationErrorCode.IN_PROGRESS);
                }
            }
        }
    }

    /** Length framing distinguishes null, empty, separators and all individual request fields. */
    private static String digest(Object... values) {
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            for (Object value : values) {
                byte[] bytes = value == null ? new byte[0] : value.toString().getBytes(StandardCharsets.UTF_8);
                hash.update(ByteBuffer.allocate(4)
                        .putInt(value == null ? -1 : bytes.length)
                        .array());
                hash.update(bytes);
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
