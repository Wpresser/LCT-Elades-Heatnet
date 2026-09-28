package ru.lct.heatnet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import ru.lct.heatnet.config.AppProperties;

/**
 * Автоконфигурация DataSource отключена: локально задания хранятся в памяти,
 * а в профиле {@code postgres} источник данных создаёт {@link ru.lct.heatnet.jobs.JobStoreConfig}.
 */
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class
})
@EnableConfigurationProperties(AppProperties.class)
public class HeatNetApplication {

    public static void main(String[] args) {
        SpringApplication.run(HeatNetApplication.class, args);
    }
}
