package com.example.momproject.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    public static final String EXCHANGE = "items.exchange";
    public static final String QUEUE = "items.queue";
    public static final String ROUTING_PREFIX = "items.";

    @Bean
    public TopicExchange itemsExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    // declared defensively; definitions.json already creates this on cluster boot
    @Bean
    public Queue itemsQueue() {
        return QueueBuilder.durable(QUEUE).quorum().build();
    }

    @Bean
    public Binding itemsBinding(Queue itemsQueue, TopicExchange itemsExchange) {
        return BindingBuilder.bind(itemsQueue).to(itemsExchange).with("items.#");
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory cf, MessageConverter mc) {
        RabbitTemplate t = new RabbitTemplate(cf);
        t.setMessageConverter(mc);
        t.setMandatory(true);
        return t;
    }
}
