package com.botmaker.studio.parser.factories;

import com.botmaker.studio.types.ResolvedType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A plugin's fresh value is asked by the type's full name too, not only by what enrichment made of it: a
 * palette call over MCP got {@code (ImageTemplateGroup) null} when only the bare simple name was asked
 * (2026-10-13).
 */
class EnrichedTypeTest {

    @Test
    void aSeedIsAskedByTheFullNameEnrichmentDropped() {
        ResolvedType given = ResolvedType.named("com.botmaker.sdk.api.vision.ImageTemplateGroup");
        ResolvedType enriched = ResolvedType.named("ImageTemplateGroup");

        assertEquals(List.of("ImageTemplateGroup", "com.botmaker.sdk.api.vision.ImageTemplateGroup"),
                InitializerFactory.seedNames(given, enriched));
    }

    @Test
    void aNameIsAskedOnce() {
        ResolvedType group = ResolvedType.named("com.botmaker.sdk.api.vision.ImageTemplateGroup");

        assertEquals(List.of("com.botmaker.sdk.api.vision.ImageTemplateGroup"),
                InitializerFactory.seedNames(group, group));
    }
}
