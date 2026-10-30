package com.linkforge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LinkforgeApplication {

	public static void main(String[] args) {
		SpringApplication.run(LinkforgeApplication.class, args);
	}

}
