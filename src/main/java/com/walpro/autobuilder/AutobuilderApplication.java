package com.walpro.autobuilder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
public class AutobuilderApplication {
    public static void main(String[] args) {
        SpringApplication.run(AutobuilderApplication.class, args);
    }
}