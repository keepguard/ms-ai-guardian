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
        return QueueBuilder.durable(GUARDIAN_INCIDENT_DLQ)
                .withArgument("x-message-ttl", 604800000)
                .build();
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

    @Bean
    public org.springframework.amqp.rabbit.retry.MessageRecoverer customMessageRecoverer(
            org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate) {
        
        var recoverer = new org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer(
                rabbitTemplate,
                GUARDIAN_INCIDENT_EXCHANGE,
                GUARDIAN_INCIDENT_DLQ_ROUTING_KEY
        );

        // Enriquecimento com cabeçalhos forenses de alta rastreabilidade
        recoverer.setHeaderNames(
                "x-exception-message",
                "x-exception-stacktrace",
                "x-original-queue"
        );
        return recoverer;
    }

    @Bean
    public org.springframework.amqp.rabbit.listener.RabbitListenerContainerFactory<?> rabbitListenerContainerFactory(
            org.springframework.amqp.rabbit.connection.ConnectionFactory connectionFactory,
            MessageConverter messageConverter,
            org.springframework.amqp.rabbit.retry.MessageRecoverer customMessageRecoverer) {
        
        var factory = new org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        
        // Configura interceptor de retry resiliente com backoff exponencial
        var retryTemplate = new org.springframework.retry.support.RetryTemplate();
        var backOffPolicy = new org.springframework.retry.backoff.ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(1000);
        backOffPolicy.setMultiplier(2.0);
        backOffPolicy.setMaxInterval(5000);
        retryTemplate.setBackOffPolicy(backOffPolicy);

        var retryPolicy = new org.springframework.retry.policy.SimpleRetryPolicy(3);
        retryTemplate.setRetryPolicy(retryPolicy);

        var advice = org.springframework.amqp.rabbit.config.RetryInterceptorBuilder.stateless()
                .retryOperations(retryTemplate)
                .recoverer(customMessageRecoverer)
                .build();

        factory.setAdviceChain(advice);
        return factory;
    }
}
