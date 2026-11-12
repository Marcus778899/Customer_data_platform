package com.cdp.persistence;

import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 持久層整合測試用：一個 JVM 共用一個 PostgreSQL 容器，每次 {@link #fresh()} 建一個已套用 migration 的全新 database。 */
final class PgTestDb {

    private static final class Holder {
        static final PostgreSQLContainer PG = start();

        private static PostgreSQLContainer start() {
            PostgreSQLContainer c = new PostgreSQLContainer("postgres:18-alpine");
            c.start();
            return c;
        }
    }

    private PgTestDb() {}

    static JdbcClient fresh() {
        String db = "db_" + UUID.randomUUID().toString().replace("-", "");
        PostgreSQLContainer pg = Holder.PG;
        DriverManagerDataSource admin =
                new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        JdbcClient.create(admin).sql("create database " + db).update();
        String url = pg.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + db + "$1");
        DataSource ds = new DriverManagerDataSource(url, pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(ds).load().migrate();
        return JdbcClient.create(ds);
    }
}
