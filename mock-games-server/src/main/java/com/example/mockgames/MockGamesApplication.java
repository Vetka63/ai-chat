package com.example.mockgames;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MockGamesApplication {
    public static void main(String[] args) {
        SpringApplication.run(MockGamesApplication.class, args);
    }
}
