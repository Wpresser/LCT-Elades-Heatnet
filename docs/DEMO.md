# Демонстрация финального release

1. `docker compose up -d`: backend на http://localhost:8080/, Swagger на `/swagger-ui.html`.
2. Из корня `python scripts/api_e2e.py`: загрузка официального набора, отдельный start, polling DONE,
   скачивание output и три независимые проверки. Default `HEATNET_TERMINAL_POLICY=literal`.
3. `cd viewer && npm ci && npm run dev`: открыть http://localhost:5173/viewer/.
4. Strict открыт по умолчанию: 14/17, S 20.589, 1.554 км, calculated cost 568.862 млн ₽.
   Construction cost 229.867 млн ₽; penalty 338.995 млн ₽. Нажатие на неподключённый ОКС показывает
   backend-причину, literal policy и штраф по §2.5 / §6 без optional proof-файлов.
5. Показать инженерные подписи DN/flow, общие магистрали, камеры, источник, слои, 3D и вид сверху.
6. Отдельно переключить alternative: 17/17, S 13.076, 1.825 км, 271.516 млн ₽. Подчеркнуть раскрытую
   альтернативную трактовку §2.2 без официального подтверждения; вернуть strict перед сдачей.

Точные значения и оба ranked variants: `FINAL_METRICS.json`. Фактически выполненные проверки,
команды, warnings и CI evidence: `FINAL_RELEASE_REPORT.md`. Нагрузочные утверждения прежних стадий
не входят в текущую демонстрацию и не обозначаются как release PASS.
