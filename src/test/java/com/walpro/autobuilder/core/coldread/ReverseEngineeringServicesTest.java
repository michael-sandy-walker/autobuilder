package com.walpro.autobuilder.core.coldread;

import com.walpro.autobuilder.core.coldread.entry.EntryKind;
import com.walpro.autobuilder.core.coldread.entry.EntryPoint;
import com.walpro.autobuilder.core.coldread.entry.LibraryStyleAdapter;
import com.walpro.autobuilder.core.coldread.entry.ProgramAdapter;
import com.walpro.autobuilder.core.merge.CapabilityDiscovery;
import com.walpro.autobuilder.core.services.ProjectServices;
import com.walpro.autobuilder.core.services.ServiceNeeds;
import com.walpro.autobuilder.core.services.ServiceProposal;
import com.walpro.autobuilder.core.services.ServicesNeeded;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T4-EN: reverse engineering asks before minutes of model work on a project that cannot start for want of a service. */
class ReverseEngineeringServicesTest {

    @TempDir
    Path source;
    @TempDir
    Path work;
    @TempDir
    Path project;

    private final ProgramAdapter program = mock(ProgramAdapter.class);
    private final CapabilityDiscovery discovery = mock(CapabilityDiscovery.class);
    private ProjectServices recorded;
    private ReverseEngineeringService service;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(source.resolve("src/main/java/shop"));
        Files.writeString(source.resolve("src/main/java/shop/Shop.java"), "package shop; public class Shop { public void list() { } }");
        Files.createDirectories(source.resolve("src/main/resources"));
        Files.writeString(source.resolve("src/main/resources/application.properties"), "db.url=jdbc:mysql://localhost:3306/shop\n");
        when(program.find(any(), any())).thenReturn(List.of(new EntryPoint(EntryKind.SERVICE_STYLE, "GET /", "Shop", "shop.Shop", "list")));
        when(program.startFailure(any())).thenReturn("CJCommunicationsException: Communications link failure");
        when(discovery.discover(any(), any(), any())).thenThrow(new IllegalStateException("went on to the model work"));
        recorded = new ProjectServices(project.toString());
        service = new ReverseEngineeringService(mock(ColdReadService.class), discovery, null, program, mock(LibraryStyleAdapter.class));
        service.setProjectServices(recorded);
        service.setServiceNeeds(new ServiceNeeds(prompt -> "IMAGE: mysql:8.0\nCONTAINER_PORT: 3306", port -> false));
    }

    @Test
    void aProjectThatDoesNotStartForWantOfAServiceStopsAndAsksBeforeTheModelWork() {
        assertThatThrownBy(() -> service.reverseEngineer(source, work))
                .isInstanceOfSatisfying(ServicesNeeded.class, n -> assertThat(n.proposals()).singleElement()
                        .satisfies(p -> {
                            assertThat(p.image()).isEqualTo("mysql:8.0");
                            assertThat(p.failure()).contains("Communications link failure");
                        }));
        verify(discovery, never()).discover(any(), any(), any());
    }

    @Test
    void anAnswerAlreadyGivenIsNotAskedAgain() {
        recorded.record(new ProjectServices.Decision(false, List.<ServiceProposal>of(), Instant.now()));
        assertThatThrownBy(() -> service.reverseEngineer(source, work)).hasMessage("went on to the model work");
        verify(program, never()).startFailure(any());
    }

    @Test
    void aYesThatDoesNotComeUpIsAskedAgainWithWhatItSaid() {
        ServiceProposal yes = new ServiceProposal("mysql", "mysql:8", 3306, 3306, Map.of("MYSQL_ROOT_PASSWORD", ""), null,
                List.of(), null, "model", List.of("a:1: db.password="), "link failure");
        recorded.record(new ProjectServices.Decision(true, List.of(yes), Instant.now()));
        var containers = mock(com.walpro.autobuilder.core.services.ServiceContainers.class);
        var failed = mock(com.walpro.autobuilder.core.services.ServiceContainers.Started.class);
        when(failed.failed()).thenReturn(true);
        when(failed.failedOn()).thenReturn(yes);
        when(failed.said()).thenReturn("You need to specify MYSQL_ALLOW_EMPTY_PASSWORD");
        when(failed.why()).thenReturn("mysql (mysql:8) stopped while starting");
        when(containers.startFresh(any())).thenReturn(failed);
        service.setServiceContainers(containers);
        service.setServiceNeeds(new ServiceNeeds(prompt -> "IMAGE: mysql:8\nCONTAINER_PORT: 3306\nENV: MYSQL_ALLOW_EMPTY_PASSWORD=yes",
                port -> false));
        assertThatThrownBy(() -> service.reverseEngineer(source, work))
                .isInstanceOfSatisfying(ServicesNeeded.class, n -> assertThat(n.proposals()).singleElement().satisfies(p -> {
                    assertThat(p.env()).containsEntry("MYSQL_ALLOW_EMPTY_PASSWORD", "yes");
                    assertThat(p.failure()).contains("stopped while starting");
                }));
        verify(discovery, never()).discover(any(), any(), any());
        verify(failed).close();
    }

    @Test
    void aYesThatComesUpGoesOnToTheWork() {
        recorded.record(new ProjectServices.Decision(true, List.of(new ServiceProposal("mysql", "mysql:8", 3306, 3306, Map.of(),
                null, List.of(), null, "model", List.of(), "x")), Instant.now()));
        var containers = mock(com.walpro.autobuilder.core.services.ServiceContainers.class);
        var up = mock(com.walpro.autobuilder.core.services.ServiceContainers.Started.class);
        when(containers.startFresh(any())).thenReturn(up);
        service.setServiceContainers(containers);
        assertThatThrownBy(() -> service.reverseEngineer(source, work)).hasMessage("went on to the model work");
        verify(up).close();
    }

    @Test
    void aProjectThatStartsAnywayIsNotAsked() {
        when(program.startFailure(any())).thenReturn(null);
        assertThatThrownBy(() -> service.reverseEngineer(source, work)).hasMessage("went on to the model work");
    }
}
