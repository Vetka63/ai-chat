package com.example.aichat;

import com.example.aichat.config.ChatProperties;
import com.example.aichat.config.MistralProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** Запускает Java backend как приложение Spring Boot. */
@SpringBootApplication
@EnableConfigurationProperties({ChatProperties.class, MistralProperties.class})
public class AiChatBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiChatBackendApplication.class, args);
    }
}
