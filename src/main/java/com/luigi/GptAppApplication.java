package com.luigi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class GptAppApplication {
	public static void main(String[] args) {
		SpringApplication.run(GptAppApplication.class, args);
	}

	@Bean
	public FilterRegistrationBean<AuthFilter> authFilter() {
		FilterRegistrationBean<AuthFilter> registration = new FilterRegistrationBean<>();
		registration.setFilter(new AuthFilter());
		registration.addUrlPatterns("/*");
		return registration;
	}

}
