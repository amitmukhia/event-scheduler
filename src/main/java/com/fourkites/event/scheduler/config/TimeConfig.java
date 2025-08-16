package com.fourkites.event.scheduler.config;

import jakarta.validation.ClockProvider;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Clock;
import org.hibernate.validator.HibernateValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class TimeConfig {

  @Bean
  @Primary
  public Clock clock() {
    // Central Clock bean using UTC for consistent, testable time handling
    return Clock.systemUTC();
  }

  @Bean
  public ClockProvider clockProvider(Clock clock) {
    // Make Bean Validation use UTC for @Future/@Past checks
    return () -> clock;
  }

  @Bean
  @Primary
  public Validator validator(Clock clock) {
    // Create validator that uses UTC clock for time-based validations
    ClockProvider clockProvider = () -> clock;

    ValidatorFactory factory =
        Validation.byProvider(HibernateValidator.class)
            .configure()
            .clockProvider(clockProvider)
            .buildValidatorFactory();

    return factory.getValidator();
  }
}
