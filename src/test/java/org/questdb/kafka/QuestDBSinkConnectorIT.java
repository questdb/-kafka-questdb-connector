package org.questdb.kafka;

import io.debezium.testing.testcontainers.ConnectorConfiguration;
import io.debezium.testing.testcontainers.DebeziumContainer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import static java.time.Duration.ofMinutes;

public class QuestDBSinkConnectorIT {
    private static Network network = Network.newNetwork();

    private static final KafkaContainer kafkaContainer = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.2.0"))
            .withNetwork(network);

    private static final GenericContainer<?> questDBContainer = new GenericContainer<>("questdb/questdb:6.5.2")
            .withNetwork(network)
            .withExposedPorts(9000)
            .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("questdb")))
            .withEnv("QDB_CAIRO_COMMIT_LAG", "100")
            .withEnv("JAVA_OPTS", "-Djava.locale.providers=JRE,SPI");

    private static final DebeziumContainer connectContainer = new DebeziumContainer("confluentinc/cp-kafka-connect:7.2.1")
            .withEnv("CONNECT_BOOTSTRAP_SERVERS", kafkaContainer.getNetworkAliases().get(0) + ":9092")
            .withEnv("CONNECT_GROUP_ID", "test")
            .withEnv("CONNECT_OFFSET_STORAGE_TOPIC", "connect-storage-topic")
            .withEnv("CONNECT_CONFIG_STORAGE_TOPIC", "connect-config-topic")
            .withEnv("CONNECT_STATUS_STORAGE_TOPIC", "connect-status-topic")
            .withEnv("CONNECT_KEY_CONVERTER", "org.apache.kafka.connect.storage.StringConverter")
            .withEnv("CONNECT_VALUE_CONVERTER", "org.apache.kafka.connect.json.JsonConverter")
            .withEnv("CONNECT_VALUE_CONVERTER_SCHEMAS_ENABLE", "false")
            .withEnv("CONNECT_REST_ADVERTISED_HOST_NAME", "connect")
            .withEnv("CONNECT_CONFIG_STORAGE_REPLICATION_FACTOR", "1")
            .withEnv("CONNECT_OFFSET_STORAGE_REPLICATION_FACTOR", "1")
            .withEnv("CONNECT_STATUS_STORAGE_REPLICATION_FACTOR", "1")
            .withCopyToContainer(MountableFile.forHostPath("target/questdb-connector/questdb-connector.jar"), "/usr/share/java/kafka/questdb-connector.jar")
            .withCopyToContainer(MountableFile.forHostPath("target/questdb-connector/questdb-6.5.2-jdk8.jar"), "/usr/share/java/kafka/questdb-6.5.2-jdk8.jar")
            .withNetwork(network)
            .withExposedPorts(8083)
            .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("connect")))
            .dependsOn(kafkaContainer, questDBContainer)
            .waitingFor(new HttpWaitStrategy()
                    .forPath("/connectors")
                    .forStatusCode(200)
                    .forPort(8083)
                    .withStartupTimeout(ofMinutes(5)));

    @BeforeAll
    public static void startContainers() throws Exception {
        createConnectorJar();
        Startables.deepStart(Stream.of(
                        kafkaContainer, connectContainer, questDBContainer))
                .join();
    }

    @Test
    public void test() throws Exception {
        String topicName = "mytopic";
        Properties props = new Properties();

        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (Producer<String, String> producer = new KafkaProducer<>(props)) {
            RecordMetadata recordMetadata = producer.send(new ProducerRecord<>(topicName, "foo", "bar")).get();
            System.out.println(recordMetadata);
        }

        ConnectorConfiguration connector = ConnectorConfiguration.create()
                .with("connector.class", "org.questdb.kafka.QuestDBSinkConnector")
                .with("tasks.max", "1")
                .with("key.converter", "org.apache.kafka.connect.storage.StringConverter")
                .with("value.converter", "org.apache.kafka.connect.storage.StringConverter")
                .with("topics", topicName)
                .with("auto.offset.reset", "earliest")
                .with("host", questDBContainer.getNetworkAliases().get(0) + ":" + QuestDBUtils.QUESTDB_ILP_PORT);

        connectContainer.registerConnector("my-connector", connector);

        QuestDBUtils.assertSqlEventually(questDBContainer, "\"key\",\"value\"\r\n"
                + "\"foo\",\"bar\"\r\n", "select key, value from " + topicName);

    }

    private static void createConnectorJar() throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        JarOutputStream target = new JarOutputStream(new FileOutputStream("target/questdb-connector/questdb-connector.jar"), manifest);
        add(new File("target/classes"), target);
        target.close();
    }

    private static void add(File source, JarOutputStream target) throws IOException {
        String name = source.getPath().replace("\\", "/").replace("target/classes/", "");
        if (source.isDirectory()) {
            if (!name.endsWith("/")) {
                name += "/";
            }
            JarEntry entry = new JarEntry(name);
            entry.setTime(source.lastModified());
            target.putNextEntry(entry);
            target.closeEntry();
            for (File nestedFile : source.listFiles()) {
                add(nestedFile, target);
            }
        }
        else {
            JarEntry entry = new JarEntry(name);
            entry.setTime(source.lastModified());
            target.putNextEntry(entry);
            try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(source))) {
                byte[] buffer = new byte[1024];
                while (true) {
                    int count = in.read(buffer);
                    if (count == -1)
                        break;
                    target.write(buffer, 0, count);
                }
                target.closeEntry();
            }
        }
    }
}
