package com.mohanranaweera.lms_backend;

import org.springframework.boot.SpringApplication;

public class TestLmsBackendApplication {

	public static void main(String[] args) {
		SpringApplication.from(LmsBackendApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
