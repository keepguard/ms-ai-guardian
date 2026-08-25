package com.keepguard.ms_ai_guardian.infrastructure.messaging;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqTopologyConfig {

    public static final String GUARDIAN_INCIDENT_EXCHANGE = "guardian.incident.exchange";
    
    public static final String GUARDIAN_INCIDENT_QUEUE = "guardian.incident.process.queue";
    public static final String GUARDIAN_INCIDENT_ROUTING_KEY = "guardian.incident.process";

    public static final String GUARDIAN_INCIDENT_DLQ = "guardian.incident.process.dlq";
    public static final String GUARDIAN_INCIDENT_DLQ_ROUTING_KEY = "guardian.incident.process.dlq.rk";

    @Bean
    public TopicExchange guardianIncidentExchange() {
        return new TopicExchange(GUARDIAN_INCIDENT_EXCHANGE, true, false);
    }

    @Bean
    public Queue guardianIncidentQueue() {
        return QueueBuilder.durable(GUARDIAN_INCIDENT_QUEUE)
                .withArgument("x-dead-letter-exchange", GUARDIAN_INCIDENT_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", GUARDIAN_INCIDENT_DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue guardianIncidentDlq() {
        return QueueBuilder.durable(GUARDIAN_INCIDENT_DLQ).build();
    }

    @Bean
    public Binding guardianIncidentBinding(Queue guardianIncidentQueue, TopicExchange guardianIncidentExchange) {
        return BindingBuilder.bind(guardianIncidentQueue).to(guardianIncidentExchange).with(GUARDIAN_INCIDENT_ROUTING_KEY);
    }

    @Bean
    public Binding guardianIncidentDlqBinding(Queue guardianIncidentDlq, TopicExchange guardianIncidentExchange) {
        return BindingBuilder.bind(guardianIncidentDlq).to(guardianIncidentExchange).with(GUARDIAN_INCIDENT_DLQ_ROUTING_KEY);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
