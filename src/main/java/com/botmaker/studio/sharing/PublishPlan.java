package com.botmaker.studio.sharing;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Where a publish has got to: one {@link StepState} per {@link Step}, in order.
 *
 * <p>Publishing is five network operations that cannot be made one transaction — a repository, a push, a
 * release, a download check and a gallery listing. It used to be one method, so a failure at the fourth
 * reported as "Publish failed" and pressing Publish again started from the first, where the release step then
 * refused a tag that already existed. A plan says which steps are done, so a retry resumes at the one that
 * failed and a success is shown step by step rather than as one sentence.
 *
 * <p>Pure and immutable: every transition returns a new plan. {@link BotPublisher.Run} performs the steps;
 * this only records them, which is what lets the rules be tested with no network.
 */
public record PublishPlan(List<StepState> steps) {

    /** The steps of a publish, in the order they run. */
    public enum Step {
        REPO("Repository", "Create the GitHub repository, or make an existing one public"),
        PUSH("Upload", "Upload the project's files"),
        RELEASE("Release", "Cut the release other people install"),
        ARCHIVE("Download check", "Check that the release downloads without signing in"),
        LISTING("Gallery listing", "Submit the gallery entry");

        private final String label;
        private final String description;

        Step(String label, String description) {
            this.label = label;
            this.description = description;
        }

        public String label() {
            return label;
        }

        public String description() {
            return description;
        }
    }

    public enum Status { PENDING, RUNNING, DONE, FAILED, SKIPPED }

    /** One step and how it went. {@code detail} is the sentence shown beside it, blank when there is none. */
    public record StepState(Step step, Status status, String detail) {
        public StepState {
            detail = detail == null ? "" : detail;
        }
    }

    public PublishPlan {
        steps = List.copyOf(steps);
    }

    /** A fresh plan. An unlisted publish skips the listing, so it is finished when the download check is. */
    public static PublishPlan start(boolean listed) {
        List<StepState> states = new ArrayList<>();
        for (Step step : Step.values()) {
            boolean skip = step == Step.LISTING && !listed;
            states.add(new StepState(step, skip ? Status.SKIPPED : Status.PENDING,
                    skip ? "Not listed: the release is on GitHub, and nobody is told about it" : ""));
        }
        return new PublishPlan(states);
    }

    /** The step to run next — the first pending or failed one — or empty when the publish is finished. */
    public Optional<Step> next() {
        return steps.stream()
                .filter(s -> s.status() == Status.PENDING || s.status() == Status.FAILED)
                .map(StepState::step)
                .findFirst();
    }

    public StepState state(Step step) {
        return steps.stream().filter(s -> s.step() == step).findFirst().orElseThrow();
    }

    public PublishPlan running(Step step) {
        return with(step, Status.RUNNING, "");
    }

    public PublishPlan done(Step step, String detail) {
        return with(step, Status.DONE, detail);
    }

    public PublishPlan failed(Step step, String detail) {
        return with(step, Status.FAILED, detail);
    }

    /** The failed step, if the publish stopped on one. */
    public Optional<StepState> failure() {
        return steps.stream().filter(s -> s.status() == Status.FAILED).findFirst();
    }

    /** True when nothing is left to run and nothing failed. */
    public boolean finished() {
        return steps.stream().allMatch(s -> s.status() == Status.DONE || s.status() == Status.SKIPPED);
    }

    /** True once any step has finished, which is when changing what is being published stops being safe. */
    public boolean started() {
        return steps.stream().anyMatch(s -> s.status() == Status.DONE || s.status() == Status.FAILED
                || s.status() == Status.RUNNING);
    }

    private PublishPlan with(Step step, Status status, String detail) {
        List<StepState> out = new ArrayList<>(steps);
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i).step() == step) out.set(i, new StepState(step, status, detail));
        }
        return new PublishPlan(out);
    }
}
