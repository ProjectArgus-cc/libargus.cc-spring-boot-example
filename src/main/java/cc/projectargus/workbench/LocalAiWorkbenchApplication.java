package cc.projectargus.workbench;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for the Local AI Workbench Spring Boot application backed by in-process {@code libargus}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class LocalAiWorkbenchApplication {

    public static void main(String[] args) {
        SpringApplication.run(LocalAiWorkbenchApplication.class, args);
    }
}
