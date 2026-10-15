package com.unis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
public class UnisApplication {

	public static void main(String[] args) {
		// Every stored timestamp (plays, likes, deletions) is UTC wall-clock
		// time, and the race clock converts it to each jurisdiction's own day.
		// Pin the JVM to UTC so that holds on any machine, not just Railway.
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		SpringApplication.run(UnisApplication.class, args);
	}

}
