package com.botmaker.studio.sharing;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * What the gallery has done with a listing pull request, read back from GitHub.
 *
 * <p>Every fact here is written by the gallery's own workflows: the {@code validate} check run, the
 * {@code waiting} and {@code needs-maintainer} labels, and one comment starting with {@link #MARKER} that says
 * why. Studio only translates them into a sentence, so the reason an author reads in the publish dialog is the
 * reason the workflow gave, word for word.
 *
 * @param url    the pull request, blank when there is none (a maintainer commits directly)
 * @param reason the workflow's own sentence, blank when it has not said one
 */
public record ListingStatus(State state, String url, String reason) {

    /** The comment {@code automerge.yml} edits in place; its first line is this marker. */
    public static final String MARKER = "<!-- botmaker-listing -->";

    public static final String LABEL_WAITING = "waiting";
    public static final String LABEL_NEEDS_MAINTAINER = "needs-maintainer";

    /** The check run {@code validate.yml} reports as. */
    public static final String CHECK_NAME = "validate";

    public enum State {
        /** No pull request and no entry: this bot is not in the gallery. */
        NOT_LISTED("Not listed"),
        /** The entry is in the gallery. */
        LISTED("Listed"),
        /** Written straight to the gallery by a maintainer; its catalog regenerates within minutes. */
        COMMITTED("Committed"),
        /** The pull request is open and its checks have not finished, or the merge job has not run yet. */
        CHECKING("Checking"),
        /** The checks refused it. The pull request's check run says why. */
        REFUSED("Refused by the checks"),
        /** Over the new-listings limit; merges by itself at the time the comment names. */
        WAITING("Waiting"),
        /** Passes the checks but is not merged automatically; a maintainer decides. */
        NEEDS_MAINTAINER("Needs a maintainer"),
        /** Closed without merging. */
        CLOSED("Closed"),
        /** The entry already says exactly this; nothing was submitted. */
        UNCHANGED("Unchanged");

        private final String label;

        State(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public ListingStatus {
        url = url == null ? "" : url;
        reason = reason == null ? "" : reason;
    }

    public static ListingStatus of(State state) {
        return new ListingStatus(state, "", "");
    }

    /**
     * Reads a listing pull request.
     *
     * @param pr              GitHub's pull request object
     * @param checkConclusion the {@code validate} run's conclusion ({@code success}, {@code failure}, …), blank
     *                        while it runs or when it has not started
     * @param comment         the body of the gallery's sticky comment, blank when there is none
     */
    public static ListingStatus ofPullRequest(JsonNode pr, String checkConclusion, String comment) {
        String url = pr.path("html_url").asText("");
        String reason = reasonOf(comment);
        // A listed pull request carries `merged` only when fetched one at a time; `merged_at` is on both shapes.
        if (pr.path("merged").asBoolean(false) || pr.hasNonNull("merged_at")) {
            return new ListingStatus(State.LISTED, url, "");
        }
        if ("closed".equals(pr.path("state").asText())) {
            return new ListingStatus(State.CLOSED, url, "");
        }
        String conclusion = checkConclusion == null ? "" : checkConclusion;
        if (!conclusion.isBlank() && !"success".equals(conclusion) && !"neutral".equals(conclusion)
                && !"skipped".equals(conclusion)) {
            return new ListingStatus(State.REFUSED, url, "");
        }
        for (JsonNode label : pr.path("labels")) {
            String name = label.path("name").asText("");
            if (LABEL_NEEDS_MAINTAINER.equals(name)) return new ListingStatus(State.NEEDS_MAINTAINER, url, reason);
            if (LABEL_WAITING.equals(name)) return new ListingStatus(State.WAITING, url, reason);
        }
        return new ListingStatus(State.CHECKING, url, "");
    }

    /** The sentence under the marker, or blank when {@code comment} is not the gallery's. */
    static String reasonOf(String comment) {
        if (comment == null || !comment.startsWith(MARKER)) return "";
        return comment.substring(MARKER.length()).trim();
    }

    /** One line for the dialog: the state, and the workflow's reason when it gave one. */
    public String describe() {
        String base = switch (state) {
            case NOT_LISTED -> "Not in the gallery.";
            case LISTED -> "Listed in the gallery.";
            case COMMITTED -> "Written to the gallery. It shows once the catalog regenerates, within minutes.";
            case CHECKING -> "Submitted. The gallery's checks are running; it merges by itself when they pass.";
            case REFUSED -> "The gallery's checks refused it. Open the pull request to read why.";
            case WAITING -> "Passes the checks, and waits for the new-listings limit.";
            case NEEDS_MAINTAINER -> "Passes the checks, but a maintainer has to merge it.";
            case CLOSED -> "The pull request was closed without merging.";
            case UNCHANGED -> "The gallery already lists exactly this entry.";
        };
        return reason.isBlank() ? base : base + " " + reason;
    }
}
