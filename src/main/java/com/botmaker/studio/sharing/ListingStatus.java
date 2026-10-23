package com.botmaker.studio.sharing;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * What the catalog has done with a bot's entry, read back from GitHub (2026-10-23).
 *
 * <p>Every fact here is written by the catalog's own workflows: the {@code validate} check run, the
 * {@code waiting} label, and the one comment starting with {@link #MARKER} that says why. Studio only turns them
 * into a sentence, so the reason an author reads in the publish sheet is the workflow's own, word for word. The
 * same shape stood here for the gallery until it was deleted the same day.
 *
 * @param url    the pull request, blank when there is none
 * @param reason the workflow's own words, blank when it has said none
 */
public record ListingStatus(State state, String url, String reason) {

    /** The comment {@code automerge.yml} edits in place; its first line is this marker. */
    public static final String MARKER = "<!-- botmaker-catalog -->";

    /** The label {@code automerge.yml} puts on an entry over the new-entries limit. */
    public static final String LABEL_WAITING = "waiting";

    /** The check run {@code validate.yml} reports as: its job id. */
    public static final String CHECK_NAME = "validate";

    public enum State {
        /** No pull request open and no entry: the catalog does not list this bot. */
        NOT_LISTED,
        /** The entry is on the catalog's {@code main}; Studio shows it once the catalog is rebuilt. */
        LISTED,
        /** The pull request is open and its check has not finished, or the merge job has not run yet. */
        OPEN,
        /** Passes the check, and waits for the new-entries limit; merges by itself at the time the comment names. */
        WAITING,
        /** The check refused it. */
        REFUSED,
        /** Closed without merging. */
        CLOSED,
        /** The entry already says exactly this; nothing was opened. */
        UNCHANGED
    }

    public ListingStatus {
        url = url == null ? "" : url;
        reason = reason == null ? "" : reason;
    }

    public static ListingStatus of(State state) {
        return new ListingStatus(state, "", "");
    }

    /**
     * Reads a catalog pull request.
     *
     * @param pr              GitHub's pull request object
     * @param checkConclusion the {@code validate} run's conclusion ({@code success}, {@code failure}, …), blank
     *                        while it runs or before it starts
     * @param comment         the body of the catalog's comment, blank when there is none
     * @param refusal         what the refused check said, blank when it said nothing readable
     */
    public static ListingStatus ofPullRequest(JsonNode pr, String checkConclusion, String comment, String refusal) {
        String url = pr.path("html_url").asText("");
        // A listed pull request carries `merged` only when fetched one at a time; `merged_at` is on both shapes.
        if (pr.path("merged").asBoolean(false) || pr.hasNonNull("merged_at")) {
            return new ListingStatus(State.LISTED, url, "");
        }
        if ("closed".equals(pr.path("state").asText())) {
            return new ListingStatus(State.CLOSED, url, "");
        }
        // Only a failure is the check's no: a cancelled or timed-out run says nothing about the entry, and the
        // next run (a new push, the hourly merge job) answers again.
        if ("failure".equals(checkConclusion)) return new ListingStatus(State.REFUSED, url, refusal);
        for (JsonNode label : pr.path("labels")) {
            if (LABEL_WAITING.equals(label.path("name").asText(""))) {
                return new ListingStatus(State.WAITING, url, reasonOf(comment));
            }
        }
        return new ListingStatus(State.OPEN, url, "");
    }

    /** The words under the marker, or blank when {@code comment} is not the catalog's. */
    static String reasonOf(String comment) {
        if (comment == null || !comment.startsWith(MARKER)) return "";
        return comment.substring(MARKER.length()).trim();
    }

    /** True while the catalog may still change its answer: the sheet offers to read it again. */
    public boolean pending() {
        return state == State.OPEN || state == State.WAITING;
    }

    /** One line for the sheet: the state, and the workflow's reason when it gave one. */
    public String describe() {
        String base = switch (state) {
            case NOT_LISTED -> "Not in the catalog.";
            case LISTED -> "Listed in the catalog. Browse Bots and New Project show it once the catalog is rebuilt, "
                    + "within minutes.";
            case OPEN -> "Pull request open. The catalog's check is running; it merges by itself when it passes.";
            case WAITING -> "Passes the check, and waits for the new-entries limit.";
            case REFUSED -> "The catalog's check refused it.";
            case CLOSED -> "The pull request was closed without merging.";
            case UNCHANGED -> "The catalog already lists exactly this.";
        };
        if (!reason.isBlank()) return base + " " + reason;
        return state == State.REFUSED && !url.isBlank() ? base + " Its pull request says why: " + url : base;
    }
}
