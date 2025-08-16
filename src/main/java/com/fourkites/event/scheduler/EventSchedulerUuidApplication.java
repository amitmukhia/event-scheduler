package com.fourkites.event.scheduler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@org.springframework.boot.context.properties.ConfigurationPropertiesScan
public class EventSchedulerUuidApplication {
  public static void main(String[] args) {
    SpringApplication.run(EventSchedulerUuidApplication.class, args);
  }
}
