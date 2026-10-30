package com.cdp.acceptance.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Base64;
import java.util.UUID;

import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * 驗收測試共用容器（ci-speedup 做法 #3）：一個 JVM 只起一組 PostgreSQL 與 RabbitMQ（lazy singleton）。
 * 不交給 Spring 管生命週期，JVM 結束時由 Ryuk 清掉。每個 Spring context 呼叫 {@link #registrar} 取得自己的
 * database 與 vhost，資料與佇列互不相見。會 {@code docker pause} 容器的測試不走這裡，維持專屬容器。
 */
public final class SharedContainers {

    private static final String POSTGRES_IMAGE = "postgres:18-alpine";
    private static final String RABBIT_IMAGE = "rabbitmq:4.3-management-alpine";

    private SharedContainers() {}

    private static final class PgHolder {
        static final PostgreSQLContainer INSTANCE = start();

        private static PostgreSQLContainer start() {
            // 每個 context 一個 Hikari pool（預設 10 條），快取的 context 最多 32 個，預設 100 條連線不夠
            PostgreSQLContainer c =
                    new PostgreSQLContainer(POSTGRES_IMAGE).withCommand("postgres", "-c", "max_connections=1000");
            c.start();
            return c;
        }
    }

    private static final class RabbitHolder {
        static final RabbitMQContainer INSTANCE = start();

        private static RabbitMQContainer start() {
            RabbitMQContainer c = new RabbitMQContainer(RABBIT_IMAGE);
            c.start();
            return c;
        }
    }

    /**
     * 回傳 DynamicPropertyRegistrar：建立專屬 database 與 vhost 並登記連線屬性。
     *
     * @param dedicated true 表示該 context 用專屬容器（由 @ServiceConnection bean 提供），這裡什麼都不做
     */
    public static DynamicPropertyRegistrar registrar(boolean dedicated) {
        if (dedicated) {
            return registry -> {};
        }
        return SharedContainers::register;
    }

    private static void register(DynamicPropertyRegistry registry) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String database = "db_" + suffix;
        String vhost = "vh_" + suffix;

        PostgreSQLContainer pg = PgHolder.INSTANCE;
        try (Connection conn =
                        DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                Statement st = conn.createStatement()) {
            st.execute("create database \"" + database + "\"");
        } catch (Exception e) {
            throw new IllegalStateException("cannot create database " + database, e);
        }
        String url = "jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/" + database;
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", pg::getUsername);
        registry.add("spring.datasource.password", pg::getPassword);

        RabbitMQContainer mq = RabbitHolder.INSTANCE;
        createVhost(mq, vhost);
        registry.add("spring.rabbitmq.host", mq::getHost);
        registry.add("spring.rabbitmq.port", () -> mq.getAmqpPort());
        registry.add("spring.rabbitmq.username", mq::getAdminUsername);
        registry.add("spring.rabbitmq.password", mq::getAdminPassword);
        registry.add("spring.rabbitmq.virtual-host", () -> vhost);
    }

    private static void createVhost(RabbitMQContainer mq, String vhost) {
        String auth =
                "Basic "
                        + Base64.getEncoder()
                                .encodeToString(
                                        (mq.getAdminUsername() + ":" + mq.getAdminPassword())
                                                .getBytes(StandardCharsets.UTF_8));
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        put(http, auth, mq.getHttpUrl() + "/api/vhosts/" + vhost, "{}");
        put(
                http,
                auth,
                mq.getHttpUrl() + "/api/permissions/" + vhost + "/" + mq.getAdminUsername(),
                "{\"configure\":\".*\",\"write\":\".*\",\"read\":\".*\"}");
    }

    private static void put(HttpClient http, String auth, String url, String body) {
        try {
            HttpResponse<String> r =
                    http.send(
                            HttpRequest.newBuilder(URI.create(url))
                                    .header("Authorization", auth)
                                    .header("Content-Type", "application/json")
                                    .PUT(HttpRequest.BodyPublishers.ofString(body))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) {
                throw new IllegalStateException("PUT " + url + " -> " + r.statusCode() + " " + r.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("PUT " + url, e);
        }
    }
}
