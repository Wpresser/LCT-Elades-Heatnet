package ru.lct.heatnet.jobs;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

public class JobStoreConfig {

    /** Локальный запуск: без БД. */
    @Configuration
    @Profile("!postgres")
    static class Memory {
        @Bean
        JobStore jobStore() {
            return new InMemoryJobStore();
        }
    }

    /** Основной режим в docker-compose: PostgreSQL. */
    @Configuration
    @Profile("postgres")
    @EnableConfigurationProperties(DataSourceProperties.class)
    static class Postgres {

        @Bean
        @ConfigurationProperties("spring.datasource.hikari")
        HikariDataSource dataSource(DataSourceProperties properties) {
            return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        JobStore jobStore(JdbcTemplate jdbcTemplate) {
            return new JdbcJobStore(jdbcTemplate, 30, 2000);
        }
    }
}
