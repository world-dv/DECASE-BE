package com.skala.decase;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.retry.annotation.EnableRetry;

@EnableJpaRepositories
@EnableJpaAuditing
@SpringBootApplication
@EnableRetry
public class DecaseApplication {

	public static void main(String[] args) {
		SpringApplication.run(DecaseApplication.class, args);
	}

}
