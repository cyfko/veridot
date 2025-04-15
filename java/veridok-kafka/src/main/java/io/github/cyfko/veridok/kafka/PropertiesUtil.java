package io.github.cyfko.veridok.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;

import java.util.Properties;

abstract class PropertiesUtil {

    /**
     * Add or overwrite <>props</> with the unique properties requirement of the Kafka implementation of the library.
     * @param props <>Properties</> to overwrite.
     */
    static void addUniqueKafkaProperties(Properties props) {
        props.setProperty(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer");
        props.setProperty(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer");
        props.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        props.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        props.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");

        if (!props.containsKey(VerifierConfig.EMBEDDED_DB_PATH_CONFIG)){
            props.setProperty(VerifierConfig.EMBEDDED_DB_PATH_CONFIG, Constant.EMBEDDED_DATABASE_PATH);
        }

        if (!props.containsKey(VerifierConfig.BROKER_TOPIC_CONFIG)){
            props.setProperty(VerifierConfig.BROKER_TOPIC_CONFIG, Constant.KAFKA_TOKEN_VERIFIER_TOPIC);
        }

        if (!props.containsKey(SignerConfig.BROKER_TOPIC_CONFIG)){
            props.setProperty(SignerConfig.BROKER_TOPIC_CONFIG, Constant.KAFKA_TOKEN_VERIFIER_TOPIC);
        }
    }

    /**
     * Overwrite the `ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG` and `ProducerConfig.BOOTSTRAP_SERVERS_CONFIG` values with
     * the provided `boostrapServers`.
     * @param props Properties to update.
     * @param boostrapServers A comma-separated list of Kafka boostrap servers.
     * @return updated Properties.
     */
    static Properties of(Properties props, String boostrapServers){
        props.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, boostrapServers);
        props.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, boostrapServers);
        return props;
    }
}
