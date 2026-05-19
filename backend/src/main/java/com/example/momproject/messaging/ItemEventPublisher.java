package com.example.momproject.messaging;

import com.example.momproject.config.RabbitConfig;
import com.example.momproject.item.ItemEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class ItemEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ItemEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public ItemEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publish(ItemEvent event) {
        String routingKey = RabbitConfig.ROUTING_PREFIX + event.type();
        rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, routingKey, event, message -> {
            message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            return message;
        });
        log.info("published event type={} itemId={} routingKey={}", event.type(), event.itemId(), routingKey);
    }
}
