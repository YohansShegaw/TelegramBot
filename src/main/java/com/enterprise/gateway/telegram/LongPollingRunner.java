package com.enterprise.gateway.telegram;

import com.enterprise.gateway.config.TelegramProperties;
import com.enterprise.gateway.service.RoutingService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class LongPollingRunner implements SmartLifecycle {

    private final TelegramApiClient telegramApiClient;
    private final RoutingService routingService;
    private final TelegramProperties properties;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread pollingThread;
    private long offset = 0L;

    public LongPollingRunner(
            TelegramApiClient telegramApiClient,
            RoutingService routingService,
            TelegramProperties properties
    ) {
        this.telegramApiClient = telegramApiClient;
        this.routingService = routingService;
        this.properties = properties;
    }

    @Override
    public void start() {
        if (properties.polling() == null || !properties.polling().enabled()) {
            return;
        }
        if (running.compareAndSet(false, true)) {
            pollingThread = Thread.ofVirtual()
                    .name("telegram-long-poller")
                    .start(this::pollLoop);
        }
    }

    private void pollLoop() {
        int timeoutSeconds = properties.polling() != null
                ? properties.polling().timeoutSecondsOrDefault()
                : 30;

        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                JsonNode updates = telegramApiClient.getUpdates(offset, timeoutSeconds);
                if (updates != null && updates.isArray()) {
                    for (JsonNode update : updates) {
                        long updateId = update.path("update_id").asLong();
                        if (updateId >= offset) {
                            offset = updateId + 1;
                        }
                        try {
                            routingService.handleUpdate(update);
                        } catch (Exception ex) {
                            System.err.println("Error handling update " + updateId + ": " + ex.getMessage());
                        }
                    }
                }
            } catch (Exception ex) {
                if (!running.get() || Thread.currentThread().isInterrupted()) {
                    break;
                }
                System.err.println("Polling error, retrying in 3s: " + ex.getMessage());
                try {
                    Thread.sleep(3000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    @Override
    public void stop() {
        running.set(false);
        if (pollingThread != null) {
            pollingThread.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}