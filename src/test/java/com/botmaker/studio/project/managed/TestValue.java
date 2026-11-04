package com.botmaker.studio.project.managed;

import com.botmaker.plugin.api.managed.ManagedId;
import com.botmaker.plugin.api.managed.ManagedMarker;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A plugin's typed marker, as a test plugin would ship it: on the test classpath, so a bound parse resolves it. */
@ManagedMarker
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
public @interface TestValue {

    Id value();

    enum Id implements ManagedId<Holder> {
        GREETING, FAREWELL, NAMES, REST_BETWEEN, PICTURES, FLOW, MINE, OPENED_ONLY, WORDS, WORDS_TOO, OUTCOMES,
        GREETING_V1, OUTCOMES_V1, ACTIVITIES, DO_SETUP, PAUSE, OLD_SHOTS, SHOTS, OLD_FRAMES, FRAMES, OLD_PAINTS, PAINTS,
        SCREENS
    }

    enum Holder {
        SDK, VALUES, NAMES, PICTURES, WORDS, OUTCOMES, ACTIVITIES, SCREENS, STEPS, SHOTS, OLD_SHOTS, FRAMES, OLD_FRAMES,
        PAINTS, OLD_PAINTS
    }
}
