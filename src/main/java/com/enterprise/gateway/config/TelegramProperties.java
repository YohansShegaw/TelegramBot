package com.enterprise.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "telegram")
public record TelegramProperties(
        Bot bot,
        Polling polling,
        Delete delete
) {
    public record Bot(String token, String username) {}

    public record Polling(boolean enabled, int timeoutSeconds) {
        public int timeoutSecondsOrDefault() {
            return timeoutSeconds > 0 ? timeoutSeconds : 30;
        }
    }


    public record Delete(Boolean customerEnabled) {
        public boolean customerEnabledOrDefault() {
            return customerEnabled == null || customerEnabled;
        }
    }

    public boolean customerDeleteEnabled() {
        return delete == null || delete.customerEnabledOrDefault();
    }
}