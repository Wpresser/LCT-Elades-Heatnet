package ru.lct.heatnet.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Заголовок и описание API в Swagger UI. */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI heatnetOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Сервис моделирования трасс подключения к тепловым сетям")
                .version("1.0")
                .description("ЛЦТ 2026, задача 2. Загрузка GeoJSON (WGS 84, до 3 ГБ), расчёт в очереди заданий, "
                        + "статус и выгрузка результата в GeoJSON по разделу 7 технического приложения (ред. 18.09). "
                        + "Порядок: POST /api/jobs → GET /api/jobs/{id} (DONE) → GET /api/jobs/{id}/result."));
    }
}
