package com.memorylane.backend.photo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Removes photo files once the database change that dropped their rows has committed (ADR-0002,
 * ML-14). A database transaction cannot roll back a file delete, so the two are kept apart:
 *
 * <ol>
 *   <li>Inside the deleting transaction a {@code file_cleanup_jobs} row is written per file. It
 *       commits or rolls back together with the photo rows, so a rolled-back delete never loses a
 *       file that is still in use.</li>
 *   <li>After commit ({@code AFTER_COMMIT} event) the files are deleted right away and their jobs
 *       removed.</li>
 *   <li>A delete that fails is logged and the job stays; {@link #retryDue()} tries again later with
 *       a growing delay, so a busy disk or a restart does not leave the file behind.</li>
 * </ol>
 *
 * {@link #sweepOrphans()} covers the cases no job was written for: an upload interrupted between
 * writing its file and committing its row, and half-written temporary files.
 */
@Component
public class PhotoFileCleanup {
    static final int BATCH_SIZE = 100;
    static final Duration FIRST_RETRY = Duration.ofMinutes(1);
    static final Duration LONGEST_RETRY = Duration.ofDays(1);
    /**
     * Files younger than this are never treated as orphans: an upload writes its file a moment
     * before the row that refers to it commits, and that row is not visible until then.
     */
    static final Duration ORPHAN_AGE = Duration.ofHours(1);
    private static final int LAST_ERROR_LENGTH = 500;

    private static final Logger log = LoggerFactory.getLogger(PhotoFileCleanup.class);

    private final JdbcClient jdbc;
    private final PhotoStorage storage;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public PhotoFileCleanup(JdbcClient jdbc, PhotoStorage storage, ApplicationEventPublisher events, Clock clock) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.events = events;
        this.clock = clock;
    }

    /** Published inside the deleting transaction; handled only if that transaction commits. */
    public record Queued(List<Long> jobIds) {}

    private record Job(long id, String storageKey, int attempts) {}

    /**
     * Call from the transaction that deletes the photo rows (photo delete, memory delete in ML-13,
     * account delete). The files are removed only after that transaction commits. MANDATORY: called
     * without a transaction there would be no commit to wait for, so it fails instead. Pass only
     * keys read from the photo rows being deleted, never values from a request; even so, a file a
     * photo row still refers to is never removed.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteAfterCommit(Collection<String> storageKeys) {
        if (storageKeys.isEmpty()) return;
        List<Long> jobIds = new ArrayList<>();
        for (String key : storageKeys) {
            jobIds.add(jdbc.sql("INSERT INTO file_cleanup_jobs (storage_key) VALUES (:key) RETURNING id")
                    .param("key", key).query(Long.class).single());
        }
        events.publishEvent(new Queued(List.copyOf(jobIds)));
    }

    // Runs after the deleting transaction committed, in a transaction of its own: the caller's
    // response does not wait on or fail because of the files. If this throws (database gone right
    // after the commit), the jobs are still stored and retryDue picks them up.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteCommitted(Queued queued) {
        // SKIP LOCKED: a job the retry run is already handling is not processed twice.
        run(jdbc.sql("""
                SELECT id, storage_key, attempts FROM file_cleanup_jobs
                WHERE id IN (:ids) FOR UPDATE SKIP LOCKED
                """).param("ids", queued.jobIds()).query(PhotoFileCleanup::job).list());
    }

    /** Retries deletes that failed earlier; several backends may run this, SKIP LOCKED keeps them apart. */
    @Scheduled(fixedDelayString = "${memorylane.photos.cleanup-retry-delay:PT5M}",
            initialDelayString = "${memorylane.photos.cleanup-retry-delay:PT5M}")
    @Transactional
    public void retryDue() {
        run(jdbc.sql("""
                SELECT id, storage_key, attempts FROM file_cleanup_jobs
                WHERE next_attempt_at <= now()
                ORDER BY next_attempt_at
                LIMIT :limit FOR UPDATE SKIP LOCKED
                """).param("limit", BATCH_SIZE).query(PhotoFileCleanup::job).list());
    }

    /**
     * Removes photo files no photo row refers to and uploads that never finished, once they are
     * older than {@link #ORPHAN_AGE}. Without a database answer nothing is removed.
     */
    @Scheduled(fixedDelayString = "${memorylane.photos.orphan-sweep-delay:PT6H}",
            initialDelayString = "${memorylane.photos.orphan-sweep-delay:PT6H}")
    public void sweepOrphans() {
        Instant cutoff = clock.instant().minus(ORPHAN_AGE);
        try {
            int incomplete = storage.removeIncompleteWrites(cutoff);
            int orphans = 0;
            List<String> candidates = storage.keysStoredBefore(cutoff);
            for (int from = 0; from < candidates.size(); from += BATCH_SIZE) {
                List<String> batch = candidates.subList(from, Math.min(from + BATCH_SIZE, candidates.size()));
                Set<String> inUse = referenced(batch);
                for (String key : batch) {
                    if (inUse.contains(key)) continue;
                    storage.delete(key);
                    orphans++;
                }
            }
            if (incomplete + orphans > 0) {
                log.info("Removed {} unfinished uploads and {} photo files no photo refers to", incomplete, orphans);
            }
        } catch (IOException | DataAccessException error) {
            log.warn("Photo file sweep stopped early; it runs again on schedule", error);
        }
    }

    /** 1, 2, 4 … minutes, at most a day: a full disk or a locked file gets time to clear. */
    static Duration retryDelay(int attempts) {
        int doublings = Math.min(Math.max(attempts - 1, 0), 20);
        Duration delay = FIRST_RETRY.multipliedBy(1L << doublings);
        return delay.compareTo(LONGEST_RETRY) > 0 ? LONGEST_RETRY : delay;
    }

    private void run(List<Job> jobs) {
        if (jobs.isEmpty()) return;
        Set<String> stillUsed = referenced(jobs.stream().map(Job::storageKey).toList());
        for (Job job : jobs) {
            // Checked again right before deleting, whatever the caller passed: a file a photo row
            // still points at is never removed. Keeping a stray file costs disk space; deleting
            // one in use would lose someone's photo.
            if (stillUsed.contains(job.storageKey())) {
                log.warn("Not deleting photo file {}: a photo still refers to it", job.storageKey());
                remove(job);
                continue;
            }
            try {
                storage.delete(job.storageKey());
            } catch (IllegalArgumentException invalidKey) {
                // Not a key this storage ever created, so it never reached the file system and
                // retrying cannot help. The value itself is not logged: it may not be ours.
                log.error("Dropping file cleanup job {}: its storage key is not valid", job.id());
                remove(job);
                continue;
            } catch (IOException | RuntimeException error) {
                reschedule(job, error);
                continue;
            }
            remove(job);
        }
    }

    private Set<String> referenced(List<String> keys) {
        return new HashSet<>(jdbc.sql("SELECT storage_key FROM photos WHERE storage_key IN (:keys)")
                .param("keys", keys).query(String.class).list());
    }

    private void remove(Job job) {
        jdbc.sql("DELETE FROM file_cleanup_jobs WHERE id = :id").param("id", job.id()).update();
    }

    private void reschedule(Job job, Exception error) {
        int attempts = job.attempts() + 1;
        Duration wait = retryDelay(attempts);
        String message = error.getClass().getSimpleName() + ": " + error.getMessage();
        jdbc.sql("""
                UPDATE file_cleanup_jobs
                SET attempts = :attempts, last_error = :error,
                    next_attempt_at = now() + make_interval(secs => :seconds)
                WHERE id = :id
                """).param("attempts", attempts)
                .param("error", message.length() > LAST_ERROR_LENGTH ? message.substring(0, LAST_ERROR_LENGTH) : message)
                .param("seconds", (double) wait.toSeconds()).param("id", job.id()).update();
        log.warn("Could not delete photo file {} (attempt {}); trying again in {}", job.storageKey(), attempts, wait, error);
    }

    private static Job job(ResultSet row, int number) throws SQLException {
        return new Job(row.getLong("id"), row.getString("storage_key"), row.getInt("attempts"));
    }
}
