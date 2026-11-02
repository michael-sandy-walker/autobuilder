package com.walpro.autobuilder.core.investigate;

import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** T4-EE slice B: the offer, the question read from a reply, and the answer sent back. */
class LogConsultationTest {

    @TempDir
    Path base;

    private final LogInvestigator investigator = mock(LogInvestigator.class);

    private LogConsultation consultation(boolean enabled) throws Exception {
        Files.createDirectories(base.resolve("logs"));
        Files.writeString(base.resolve("logs/autobuilder.log"), "line one\nline two\n");
        return new LogConsultation(investigator, enabled, base.toString());
    }

    @Test
    void offerOnlyWhenOnAndTheLogExists() throws Exception {
        assertThat(consultation(true).offerBlock()).contains("#LogQuestion <your question>", "ONE question");
        assertThat(consultation(false).offerBlock()).isNull();
        assertThat(new LogConsultation(investigator, true, base.resolve("nowhere").toString()).offerBlock()).isNull();
    }

    @Test
    void onlyAReplyThatStartsWithTheMarkerIsAQuestion() {
        assertThat(LogConsultation.questionIn("  #LogQuestion which errors had Foo.java?\n")).isEqualTo("which errors had Foo.java?");
        assertThat(LogConsultation.questionIn("#Evolution\n... #LogQuestion inside a change")).isNull();
        assertThat(LogConsultation.questionIn("#LogQuestion   ")).isNull();
        assertThat(LogConsultation.questionIn(null)).isNull();
        assertThat(LogConsultation.questionIn("#LogQuestion " + "x".repeat(900))).hasSize(LogConsultation.MAX_QUESTION_CHARS);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aQuestionIsInvestigatedOverTheLiveLogs_andTheAnswerSentBackAndRecorded() throws Exception {
        LogConsultation c = consultation(true);
        BehaviourLogger behaviour = mock(BehaviourLogger.class);
        ReflectionTestUtils.setField(c, "behaviourLogger", behaviour);
        when(investigator.investigate(eq("what failed in Foo.java?"), any(Map.class), anyInt(), eq("cycle-7")))
                .thenAnswer(inv -> {
                    Map<String, List<String>> logs = inv.getArgument(1);
                    assertThat(logs).containsOnlyKeys("autobuilder.log");
                    assertThat(logs.get("autobuilder.log")).containsExactly("line one", "line two");
                    return new LogInvestigator.Investigation("what failed in Foo.java?",
                            List.of(new LogInvestigator.Step("#Probe search autobuilder.log Foo", "1 matching lines:")),
                            "Foo.java failed once (line 2).", true);
                });

        String reply = c.answer("#LogQuestion what failed in Foo.java?", "evolution", "cycle-7");

        assertThat(reply).contains("Question: what failed in Foo.java?", "Foo.java failed once (line 2).",
                "Now reply with your change");
        verify(behaviour).logQuestionAsked(eq("cycle-7"), eq("evolution"), eq("what failed in Foo.java?"),
                eq(List.of("#Probe search autobuilder.log Foo")), eq(true), eq("Foo.java failed once (line 2)."), anyLong());
    }

    @Test
    @SuppressWarnings("unchecked")
    void noAnswer_isSaidPlainly_andTheAgentGoesOnToWrite() throws Exception {
        LogConsultation c = consultation(true);
        when(investigator.investigate(anyString(), any(Map.class), anyInt(), any()))
                .thenReturn(new LogInvestigator.Investigation("q", List.of(), null, false));

        assertThat(c.answer("#LogQuestion q", "evolution", null))
                .contains("did not find an answer", "Now reply with your change");
    }

    @Test
    void aReplyThatIsNotAQuestion_orTheCapabilityOff_isLeftAlone() throws Exception {
        assertThat(consultation(true).answer("#Evolution\n[src/A.java]\n...", "evolution", null)).isNull();
        assertThat(consultation(false).answer("#LogQuestion q", "evolution", null)).isNull();
        verifyNoInteractions(investigator);
    }

    @Test
    @SuppressWarnings("unchecked")
    void anInvestigationThatThrows_isAnsweredAsNoAnswer() throws Exception {
        LogConsultation c = consultation(true);
        when(investigator.investigate(anyString(), any(Map.class), anyInt(), any())).thenThrow(new IllegalStateException("x"));

        assertThat(c.answer("#LogQuestion q", "evolution", null)).contains("did not find an answer");
    }
}
