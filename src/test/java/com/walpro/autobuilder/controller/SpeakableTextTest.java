package com.walpro.autobuilder.controller;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** T5-BO: invented paths only — the rule is about the shape of a token, not any target's layout. */
class SpeakableTextTest {

    @Test
    void aDeepSourcePathIsSpokenAsItsFileName() {
        assertThat(SpeakableText.of("Editing src/main/java/org/acme/kit/WidgetStore.java now"))
                .isEqualTo("Editing WidgetStore now");
    }

    @Test
    void aUrlIsSpokenAsItsLastSegment() {
        assertThat(SpeakableText.of("Polling http://localhost:9999/console/status"))
                .isEqualTo("Polling status");
    }

    @Test
    void aShortRouteIsSpokenAsItsWords() {
        assertThat(SpeakableText.of("POST /items/{id}/archive answered 409"))
                .isEqualTo("POST items id archive answered 409");
    }

    @Test
    void textWithoutSlashesIsUntouched() {
        assertThat(SpeakableText.of("Subgoal fulfilled. Next: widgets.")).isEqualTo("Subgoal fulfilled. Next: widgets.");
    }

    @Test
    void aCountOfATotalIsReadAsOne() {
        assertThat(SpeakableText.of("observed 3/5")).isEqualTo("observed 3 of 5");
    }
}
