# Elades — ЛЦТ 2026, задача 2

Сервис автоматически строит варианты подключения новых зданий к существующей тепловой сети. Основная часть проекта написана на Java 11 и Spring Boot 2.6.3. Для просмотра результата есть отдельная карта на React, TypeScript и MapLibre.

## Что делает сервис

- Принимает GeoJSON с сетью, камерами, зданиями и пространственными ограничениями.
- Строит варианты трасс, выбирает места присоединения и камеры разветвления.
- Рассчитывает расходы, диаметры, длину, стоимость и итоговый показатель S.
- Выгружает GeoJSON с вариантами, неподключёнными точками и пояснениями.

## Запуск сервиса

Нужны Ubuntu Server 22, Docker Engine, `docker-compose` 1.29.2 и доступ к Docker Hub/Maven Central.

```bash
docker-compose up --build -d
docker-compose logs -f app
```

После запуска доступны:

- Технический интерфейс: http://localhost:8080/
- Swagger UI: http://localhost:8080/swagger-ui.html
- Описание API: http://localhost:8080/v3/api-docs

Загрузка конкурсного набора, проверка статуса и скачивание результата:

```bash
curl -F "file=@task/sources/Датасет скорректированный.geojson" http://localhost:8080/api/jobs
curl http://localhost:8080/api/jobs/ID_ЗАДАЧИ
curl -o result.geojson http://localhost:8080/api/jobs/ID_ЗАДАЧИ/result
```

Замените `ID_ЗАДАЧИ` на идентификатор из ответа на первую команду. Скачивайте результат после перехода задачи в состояние `DONE`.

SHA-256 исправленного конкурсного набора: `cffb7133419d93fe364a53015a7d3ead289f671cbfaf6f4be87f2a21914130`.

## Карта результата

```bash
cd viewer
npm install
npm run typecheck
npm run build
npm run dev
```

Откройте http://localhost:5173/viewer/. Карта показывает сеть, здания, ограничения, камеры, источник, параметры участков и объяснения для неподключённых точек. Есть 3D и вид сверху. Это локальный viewer; ссылка на GitHub не является запущенным прототипом.

## Два режима правила §2.2

По умолчанию используется **strict/literal**: финальный участок к зданию проходит через ближайшую к точке подключения границу. На исправленном конкурсном наборе в этом режиме подключаются 14 из 17 точек, S лучшего варианта — 20,589. Точки 2, 5 и 10 остаются неподключёнными и учитываются со штрафом.

**Alternative/relaxed** допускает другой свободный выход из контура здания и подключает 17 из 17 точек, S = 13,076. Это отдельная трактовка спорного правила, а не подтверждённый организатором строгий результат. Карта загружает для неё отдельный GeoJSON.

Настройка backend: `HEATNET_TERMINAL_POLICY=literal` (по умолчанию) или `HEATNET_TERMINAL_POLICY=relaxed`. Обоснование и точные метрики — в [решении по правилу §2.2](docs/TERMINAL_POLICY_DECISION.md) и [FINAL_METRICS.json](FINAL_METRICS.json).

## Проверка

```bash
(cd service && ./mvnw test) # нужен JDK 11
(cd viewer && npm run test:policy && npm run typecheck && npm run build)
python results/tools/lct_audit.py --input "task/sources/Датасет скорректированный.geojson" --output results/final_strict.geojson --terminal-policy strict
python validator/validator.py "task/sources/Датасет скорректированный.geojson" results/final_strict.geojson
```

Результаты проведённых проверок — в [FINAL_VERIFICATION.md](FINAL_VERIFICATION.md). Java/Docker запуск в текущем Windows-окружении не подтверждён; необходимые команды для Ubuntu — в [FINAL_BLOCKERS.md](FINAL_BLOCKERS.md).

## Документация и файлы

- [Алгоритм](docs/ALGORITHM.md)
- [Сценарий демонстрации](docs/DEMO.md)
- [Решение по спорному правилу §2.2](docs/TERMINAL_POLICY_DECISION.md)
- [Результаты расчёта](results/)
- [Официальное задание, разъяснения и набор данных](task/)
- [Презентация команды Elades](presentation/LCT_Task2_final_presentation.pdf)
