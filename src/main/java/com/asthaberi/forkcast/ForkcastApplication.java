package com.asthaberi.forkcast;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ForkcastApplication {

	public static void main(String[] args) {
		SpringApplication.run(ForkcastApplication.class, args);
	}

}
