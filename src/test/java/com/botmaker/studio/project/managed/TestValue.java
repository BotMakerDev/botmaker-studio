package com.botmaker.studio.project.managed;

import com.botmaker.plugin.api.managed.ManagedMarker;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A plugin's typed marker, as a test plugin would ship it: on the test classpath, so a bound parse resolves it. */
@ManagedMarker
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface TestValue {

    Id value();

    enum Id { GREETING, FAREWELL, NAMES, REST_BETWEEN }
}
