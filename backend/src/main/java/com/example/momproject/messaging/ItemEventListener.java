package com.example.momproject.messaging;

import com.example.momproject.item.ItemEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class ItemEventListener {

    private static final Logger log = LoggerFactory.getLogger(ItemEventListener.class);

    private final String instanceId;

    public ItemEventListener() {
        this.instanceId = System.getenv().getOrDefault("HOSTNAME", "local");
    }

    @RabbitListener(queues = "items.queue")
    public void onEvent(ItemEvent event) {
        log.info("[{}] received event type={} itemId={} at={}",
                instanceId, event.type(), event.itemId(), event.occurredAt());
    }
}
