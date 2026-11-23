package com.enterprise.gateway;

import com.enterprise.gateway.config.TelegramProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(TelegramProperties.class)
public class BranchTelegramGatewayApplication {

	public static void main(String[] args) {
		SpringApplication.run(BranchTelegramGatewayApplication.class, args);
	}

}
