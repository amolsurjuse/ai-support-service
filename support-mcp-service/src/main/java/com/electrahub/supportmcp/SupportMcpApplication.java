package com.electrahub.supportmcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SupportMcpApplication {
    public static void main(String[] args) { SpringApplication.run(SupportMcpApplication.class, args); }
}
