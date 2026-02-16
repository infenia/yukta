package io.jagratha.jagratha;

import io.jagratha.jagratha.config.JagrathaConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(JagrathaConfig.class)
public class JagrathaApplication {

  public static void main(String[] args) {
    SpringApplication.run(JagrathaApplication.class, args);
  }
}
