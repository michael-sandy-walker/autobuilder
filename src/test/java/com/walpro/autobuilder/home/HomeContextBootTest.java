package com.walpro.autobuilder.home;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The home context as {@link HomeApplication#launch} boots it, on a free port. Unit tests construct the controller
 * themselves, so a context that cannot be built (field: a second constructor left Spring unable to choose, and the
 * application did not start) passes them all; this does not.
 */
class HomeContextBootTest {

    @Test
    void theHomeContextStartsAndServesItsEndpoints() {
        ProjectLauncher launcher = new ProjectLauncher(new String[0]);
        try (ConfigurableApplicationContext home = new SpringApplicationBuilder(HomeApplication.class)
                .profiles("home").properties("autobuilder.home.port=0", "autobuilder.home.projects-dir=./test-root")
                .initializers(ctx -> ctx.getBeanFactory().registerSingleton("projectLauncher", launcher))
                .run()) {
            assertThat(home.getBean(HomeController.class).state()).containsEntry("state", "IDLE");
            assertThat(home.getEnvironment().getProperty("local.server.port")).isNotEqualTo("0");
        }
    }
}
