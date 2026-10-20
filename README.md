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

Если стенд уже поднимался и `docker-compose up` пишет `Network … needs to be recreated` (так бывает у docker-compose 1.29.2 с новым Docker Engine), выполните `docker-compose down` — задания и база сохраняются в томах — и повторите `docker-compose up --build -d`.

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

SHA-256 исправленного конкурсного набора: `cffb7133419d93fe364a53015a7d3ead289f671cbfaf6f4befe87f2a21914130`.

## Карта результата

```bash
cd viewer
npm ci
npm run test:policy
npm run typecheck
npm run build
npm run dev
```

Нужен Node.js 22 (≥ 22.12) или 20 (≥ 20.19). Откройте http://localhost:5173/viewer/. Карта показывает сохранённые результаты конкурсного набора (`viewer/public/data/`): сеть, здания, ограничения, камеры, источник, параметры участков и объяснения для неподключённых точек, в основном и строгом режимах. Есть 3D и вид сверху. Свой файл рассчитывается и показывается в техническом интерфейсе сервиса (http://localhost:8080/).

## Доработка после обратной связи

Все 17 точек подключены в основном режиме. Трассы выравниваются вдоль локальных осей застройки (`HEATNET_ALIGN`): 64 % длины v1 вдоль осей против 53 % в сдаче 30.09; поток в камерах поворачивает не больше чем на 90°. Спецпроход через газ, кабель и теплосеть — ровно ±2 м. На наборе с дорогами из OpenStreetMap (`scenarios/osm_roads`) расчёт шёл больше 16 минут, теперь — около 2 минут, и исправлено падение. Что, как и чем проверено — в [docs/EXPERT_FEEDBACK.md](docs/EXPERT_FEEDBACK.md).

## Ввод в здание (§2.2) и режимы

По умолчанию (`relaxed`) финальный прямой участок идёт от ближайшей точки **внешнего** контура здания. Если прямой выход оттуда закрыт по входным данным (своим зданием, двором или соседним объектом) или из него нет допустимой трассы, берётся другая точка внешнего контура со свободным выходом — сначала такая, где участок внутри здания длиннее ближайшего не больше чем на 10 м, а снаружи трасса удаляется от здания: финальный участок не ведётся через значительную часть здания и не идёт вдоль его другого крыла. Предел +10 м — эвристика предпочтения, а не число из правил. Если такие выходы не подходят, поиск пробует вводы без требования чистоты и с удлинением до +60 м; это раскрывается диагностикой и предупреждениями аудитора. Приоритет ближайшего внешнего контура и возможность обоснованного альтернативного ввода описаны в дополнительных разъяснениях от 29.09.2026, №15–17 ([`task/sources/Дополнительные разъяснения 29.09.2026.docx`](task/sources/Дополнительные%20разъяснения%2029.09.2026.docx); капитан 20.10.2026 подтвердил: документ прислали официальные проверяющие в согласованный чат ЛЦТ; документ согласован с ЛЦТ). На конкурсном наборе подключаются 17 из 17 точек, S лучшего варианта — 13,197. Для точек 2 и 10 участок внутри здания длиннее ближайшего пути на 3,0 и 2,3 м, точка 5 входит через ближайший внешний контур, а не через стену двора.

Строгое прочтение §2.2 (`literal`: ближайшая точка всей границы, включая стены дворов) оставлено для сравнения: 14 из 17, точки 2, 5 и 10 со штрафом, S = 20,589. Переключение — `HEATNET_TERMINAL_POLICY=literal`. Метрики обоих режимов — в [FINAL_METRICS.json](FINAL_METRICS.json), обоснование — в [решении по правилу §2.2](docs/TERMINAL_POLICY_DECISION.md).

## Проверка

Данные карты — побайтные копии эталонных файлов из `results/` и `task/sources/`. После изменения результатов выполните `python3 scripts/sync_viewer_data.py` и `python3 scripts/check_release_metrics.py --write`; CI проверяет совпадение байтов и метрик.

```bash
python3 -m venv .venv && .venv/bin/pip install -r scripts/requirements.txt   # Ubuntu: sudo apt install python3-venv
(cd service && ./mvnw test)   # JDK 11; локаль UTF-8 (export LANG=C.UTF-8) — в пути набора кириллица
(cd viewer && npm ci && npm run test:policy && npm run typecheck && npm run build)
.venv/bin/python results/tools/lct_audit.py --input "task/sources/Датасет скорректированный.geojson" --output results/final_main.geojson --terminal-policy exterior-fallback
.venv/bin/python validator/validator.py "task/sources/Датасет скорректированный.geojson" results/final_main.geojson
```

Режим аудитора `exterior-fallback` соответствует режиму сервиса `relaxed` (правило №15): вход через стену двора — ошибка, участок внутри здания длиннее ближайшего больше чем на 10 м — предупреждение. Что строгий выход действительно закрыт, проверяет валидатор. Для карт «до/после» (`results/tools/render_compare.py`, `presentation/tools/render_map.py`) дополнительно нужен `matplotlib`.

Java 11, Docker, PostgreSQL и полный API E2E проверены в CI на Ubuntu 22.04 для сдачи 30.09 (прогон на commit 57bd009, финальный коммит сдачи — 08ee21f; режим по умолчанию тогда — `literal`). Команды, результаты и ограничения той сдачи — в [FINAL_RELEASE_REPORT.md](FINAL_RELEASE_REPORT.md) и [FINAL_VERIFICATION.md](FINAL_VERIFICATION.md). CI повторяет проверки на push/main, pull request и workflow_dispatch без игнорирования ошибок.

## Документация и файлы

- [Доработка после обратной связи экспертов](docs/EXPERT_FEEDBACK.md)
- [Алгоритм](docs/ALGORITHM.md)
- [Сценарий демонстрации](docs/DEMO.md)
- [Решение по спорному правилу §2.2](docs/TERMINAL_POLICY_DECISION.md)
- [Результаты расчёта](results/)
- [Официальное задание, разъяснения и набор данных](task/)
- [Презентация команды Elades](presentation/Elades_LCT2026_task2_presentation.pdf) (PDF; исходник — `presentation/Elades_LCT2026_task2_presentation.pptx`)
- [Пять файлов для модератора: презентация, фото и два скриншота](presentation/moderator/README.md)
- [PDF для подготовки команды: объяснение, речь, карточка и вопросы](presentation/preparation/README.md)

## Окончательная проверка

Исправления после строгого ревью, свежие проверки и их границы: [FINAL_COMPLETION_REPORT.md](docs/FINAL_COMPLETION_REPORT.md). Регламент, обязательные слайды и назначение двух версий презентации: [FINAL_SUBMISSION_RULES.md](docs/FINAL_SUBMISSION_RULES.md). Подготовку начните с [объяснения проекта и 12 главных ответов](presentation/PROJECT_FOR_BEGINNERS_RU.md), затем разберите [речь на пять минут](presentation/PITCH_SCRIPT_RU.md), [короткую карточку](presentation/QUICK_CARDS_RU.md) и [72 возможных вопроса](presentation/DEFENSE_QA_RU.md). [Опасные и точные формулировки](presentation/ANSWERS_TO_AVOID_RU.md) помогают не обещать лишнего.
