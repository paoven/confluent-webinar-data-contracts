package com.paoven.datacontracts.migrations;

import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.function.Consumer;


public class ConsumerV1 {
    public static final Logger LOGGER = LoggerFactory.getLogger(Consumer.class);

    private static final String TOPIC = "membership-migration";

    private final KafkaConsumer<String, Membership> membershipConsumerV1;

    public ConsumerV1() throws IOException {
        InputStream resourceAsStream = this.getClass().getResourceAsStream("/config.properties");
        Properties props = new Properties();
        props.load(resourceAsStream);
        props.put(KafkaAvroDeserializerConfig.USE_LATEST_WITH_METADATA, "app_version=1");

        membershipConsumerV1 = new KafkaConsumer<>(props);
    }

    public void consume() {
        try (membershipConsumerV1) {
            membershipConsumerV1.subscribe(Collections.singletonList(TOPIC));
            LOGGER.info("Starting Membership consumer V1...");
            while (true) {
                ConsumerRecords<String, Membership> records = membershipConsumerV1.poll(Duration.ofMillis(1000));
                for (ConsumerRecord<String, Membership> record : records) {
                    LOGGER.info("Membership with V1 schema: {}", record.value());
                }
            }
        } catch (WakeupException e) {
            // ignore for shutdown
        } catch (Exception e) {
            LOGGER.error("Error in User Consumer v1", e);
        }
    }

    private void wakeUp() {
        membershipConsumerV1.wakeup();
    }

    public static void main(final String[] args) throws IOException {
        ConsumerV1 consumer = new ConsumerV1();

        // get a reference to the current thread
        final Thread mainThread = Thread.currentThread();

        // Adding a shutdown hook to clean up when the application exits
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            consumer.wakeUp();

            // join the main thread to give time to consumer to close correctly
            try {
                mainThread.join();
            } catch (InterruptedException e) {
                LOGGER.error(e.getMessage());
            }
        }));

        consumer.consume();
    }
}