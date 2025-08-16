package com.fourkites.event.scheduler.config;

import com.fourkites.event.scheduler.listener.RedisEventListener;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
@EnableCaching
public class RedisConfig {

  @Bean
  public RedisMessageListenerContainer redisContainer(
      RedisConnectionFactory connectionFactory, RedisEventListener redisEventListener) {
    RedisMessageListenerContainer container = new RedisMessageListenerContainer();
    container.setConnectionFactory(connectionFactory);

    // Listen to keyspace notifications for expired keys
    container.addMessageListener(redisEventListener, new ChannelTopic("__keyevent@0__:expired"));

    // Listen to direct event messages
    container.addMessageListener(redisEventListener, eventsTopic());

    return container;
  }

  @Bean
  public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
    RedisTemplate<String, Object> template = new RedisTemplate<>();
    template.setConnectionFactory(connectionFactory);
    return template;
  }

  @Bean
  public ChannelTopic topic() {
    return eventsTopic();
  }

  @Bean
  public ChannelTopic eventsTopic() {
    return new ChannelTopic("events");
  }

  @Bean
  public ChannelTopic expiredKeysTopic() {
    return new ChannelTopic("__keyevent@0__:expired");
  }
}
