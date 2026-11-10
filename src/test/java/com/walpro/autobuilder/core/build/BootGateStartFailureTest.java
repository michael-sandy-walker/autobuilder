package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** T4-EK: why a program did not start, in its own words -- the root of the exception chain it printed. */
class BootGateStartFailureTest {

    @Test
    void theDeepestCausedByNamesWhatWasMissing() {
        List<String> tail = List.of(
                "org.springframework.beans.factory.BeanCreationException: Error creating bean 'entityManagerFactory'",
                "Caused by: org.hibernate.exception.JDBCConnectionException: Unable to open JDBC Connection",
                "\tat org.hibernate.Foo.bar(Foo.java:1)",
                "Caused by: com.mysql.cj.exceptions.CJCommunicationsException: Communications link failure",
                "\tat java.base/java.net.Socket.connect(Socket.java:751)",
                "\t... 97 common frames omitted");
        assertThat(BootGate.startFailure(tail))
                .isEqualTo("com.mysql.cj.exceptions.CJCommunicationsException: Communications link failure");
    }

    @Test
    void withoutACauseItsLastLinesSayIt() {
        assertThat(BootGate.startFailure(List.of("starting", "port 8080 already in use", "\tat x.y(Z.java:1)", "")))
                .isEqualTo("starting | port 8080 already in use");
        assertThat(BootGate.startFailure(List.of())).isEqualTo("it printed nothing");
    }
}
