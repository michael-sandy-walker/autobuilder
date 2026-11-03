package com.walpro.autobuilder;

import com.walpro.autobuilder.home.HomeApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The run context: the loop bound to one project. {@code main} opens the home context first (T4-ED), which
 * starts this one on the project named at startup, or waits for one to be chosen.
 */
@SpringBootApplication
@EnableScheduling
public class AutobuilderApplication {
    public static void main(String[] args) {
        HomeApplication.launch(args);
    }
}