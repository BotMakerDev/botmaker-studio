package com.botmaker.studio.assist;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailMaskTest {

    @Test
    void anEmailReadsAsTheSamePlaceholderInAnyCaseAndComesBackForTheWindows() {
        String first = EmailMask.mask("Signing in Farm.One@example.com on LDPlayer");
        String placeholder = first.substring("Signing in ".length(), first.indexOf(" on"));
        assertTrue(placeholder.matches("account-\\d+@masked"), first);
        assertFalse(first.contains("example"));
        assertEquals("row \"" + placeholder + "\"", EmailMask.mask("row \"farm.one@EXAMPLE.com\""));
        assertEquals("press Farm.One@example.com", EmailMask.unmask("press " + placeholder), "as first shown");
        assertEquals("account-999@masked", EmailMask.unmask("account-999@masked"), "never given: kept");
        assertEquals("account-99999999999@masked", EmailMask.unmask("account-99999999999@masked"));
    }

    @Test
    void aCutNeverSplitsAPlaceholder() {
        String line = "Signed in as " + EmailMask.mask("cut.me@example.com") + " since yesterday";
        int at = line.indexOf("@masked");
        assertEquals(at + "@masked".length(), EmailMask.cutAt(line, at));
        assertEquals(5, EmailMask.cutAt(line, 5));
    }

    @Test
    void aCutEmailIsMaskedAndTextWithoutOneIsLeftAlone() {
        assertEquals("\"…@masked…\"", EmailMask.mask("\"someone.long@exam…\""));
        assertEquals("…@masked...", EmailMask.mask("someone@example..."));
        assertEquals("java.lang.Object@1a2b3c, @SdkValue", EmailMask.mask("java.lang.Object@1a2b3c, @SdkValue"));
        assertNull(EmailMask.mask(null));
        assertTrue(EmailMask.holds("a@example.org"));
        assertFalse(EmailMask.holds(EmailMask.mask("a@example.org")), "a placeholder is no email");
        String twice = EmailMask.mask("b@example.org");
        assertEquals(twice, EmailMask.mask(twice));
    }
}
