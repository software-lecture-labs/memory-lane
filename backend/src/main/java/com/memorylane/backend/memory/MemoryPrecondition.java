package com.memorylane.backend.memory;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The memory's entity tag and the PUT {@code If-Match} rules (docs/api-contract.md, "Eşzamanlı
 * güncelleme: ETag ve If-Match"). Clients treat the tag as opaque; only this class builds and
 * reads it.
 *
 * <ul>
 *   <li>Missing or blank header: {@link Required} (428).</li>
 *   <li>{@code *}, several tags, or anything that is not one quoted tag of this memory:
 *       {@link Invalid} (400).</li>
 *   <li>A weak tag ({@code W/"..."}) or another version of this memory: {@link Modified} (412).
 *       If-Match uses strong comparison (RFC 9110), so a weak tag never matches.</li>
 * </ul>
 */
final class MemoryPrecondition {
    private static final Pattern TAG = Pattern.compile("\"memory-([0-9a-f-]{36})-r[1-9][0-9]*\"");

    private MemoryPrecondition() {
    }

    /** Strong entity tag, quotes included, e.g. {@code "memory-<id>-r7"}. */
    static String tag(UUID id, long version) {
        return "\"memory-" + id + "-r" + version + "\"";
    }

    /** Throws unless {@code ifMatch} is exactly the current strong tag of the memory. */
    static void check(String ifMatch, UUID id, long version) {
        if (ifMatch == null || ifMatch.isBlank()) throw new Required();
        String value = ifMatch.strip();
        // A comma means a list of tags (several If-Match headers are joined with commas, too).
        if (value.equals("*") || value.indexOf(',') >= 0) throw new Invalid();
        boolean weak = value.startsWith("W/");
        String opaque = weak ? value.substring(2) : value;
        Matcher matcher = TAG.matcher(opaque);
        if (!matcher.matches() || !matcher.group(1).equals(id.toString())) throw new Invalid();
        if (weak || !opaque.equals(tag(id, version))) throw new Modified();
    }

    /** 428 PRECONDITION_REQUIRED. */
    static final class Required extends RuntimeException {
        Required() {
            super(null, null, false, false);
        }
    }

    /** 400 INVALID_PRECONDITION. */
    static final class Invalid extends RuntimeException {
        Invalid() {
            super(null, null, false, false);
        }
    }

    /** 412 MEMORY_MODIFIED. */
    static final class Modified extends RuntimeException {
        Modified() {
            super(null, null, false, false);
        }
    }
}
