package com.paoven.datacontracts.migrations;

import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializerConfig;
import org.apache.commons.lang3.SerializationException;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Properties;
import java.util.Random;
import java.util.Scanner;
import java.util.concurrent.ThreadLocalRandom;


public class ProducerV1{

    private static final Logger LOGGER = LoggerFactory.getLogger(ProducerV1.class);
    private static final String TOPIC = "membership-migration";

    private final KafkaProducer<String, Membership> membershipProducerV1;


    public ProducerV1() throws IOException {
        InputStream resourceAsStream = this.getClass().getResourceAsStream("/config.properties");
        Properties props = new Properties();
        props.load(resourceAsStream);

        props.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, false);
        props.put(KafkaAvroDeserializerConfig.AVRO_USE_LOGICAL_TYPE_CONVERTERS_CONFIG, true);
        props.put(KafkaAvroDeserializerConfig.USE_LATEST_VERSION, false);
        props.put(KafkaAvroSerializerConfig.USE_LATEST_WITH_METADATA, "app_version=1");


        LOGGER.info(String.format("props: %s",props));
        membershipProducerV1 = new KafkaProducer<>(props);
    }

    private void produce() {
        try {
            final Random random = ThreadLocalRandom.current();
            final LocalDate start_date = LocalDate.ofEpochDay(random.nextInt(20000));
            final LocalDate end_date = start_date.plus(1, ChronoUnit.DAYS);
            final String email = String.format("account-%s@example.com",random.nextInt(1000000));
            final String ssn = String.format("%03d-%02d-%04d",random.nextInt(999)+1, random.nextInt(99)+1 ,random.nextInt(9999)+1  );
            Membership membership = new Membership(start_date,end_date,email,ssn);
            LOGGER.info("Sending Membership V1 record {}", membership);
            ProducerRecord<String, Membership> membershipRecord = new ProducerRecord<>(TOPIC, membership);
            membershipProducerV1.send(membershipRecord);
        } catch (SerializationException serializationException) {
            LOGGER.error("Unable to serialize Membership V1 record: {}", serializationException.getCause().getMessage());
        }
        LOGGER.info("================");
    }

    public static void main(final String[] args) throws IOException {
        ProducerV1 producerRunner = new ProducerV1();
        LOGGER.info("Starting Membership Producer V1...");
        LOGGER.info("Type 'g' to generate a payload, 'e' to exit");
        // Using Scanner for Getting Input from User
        Scanner in = new Scanner(System.in);
        boolean run=true;
        while(run){
            final String command=in.nextLine().trim();
            switch(command){
                case "g":
                    producerRunner.produce();
                    break;
                case "e":
                    LOGGER.info("Exiting");
                    run=false;
                    break;
                default:
                    LOGGER.info("Type 'g' to generate a payload, 'e' to exit");
                    break;
            }
        }

    }
}

