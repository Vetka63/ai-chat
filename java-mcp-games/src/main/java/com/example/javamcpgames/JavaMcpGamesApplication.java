package com.example.javamcpgames;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Отдельный MCP-сервер; агент и mock API запускаются независимо от него. */
@SpringBootApplication
public class JavaMcpGamesApplication {

    public static void main(String[] args) {
        SpringApplication.run(JavaMcpGamesApplication.class, args);
    }
}
