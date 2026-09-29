# Что нужно проверить перед запуском

На текущем Windows-компьютере есть только Java 8 без компилятора JDK 11, а также нет Docker Engine и docker-compose. Поэтому тесты Java Maven, `docker-compose config`, сборка образа, запуск сервиса и полный E2E-сценарий загрузки/выгрузки API здесь **НЕ ПРОВЕРЕНЫ**.

Перед запуском выполните на Ubuntu 22:

```bash
sudo apt-get update
sudo apt-get install -y openjdk-11-jdk docker.io docker-compose
cd /path/to/LCT_Task2_FINAL_SUBMISSION
java -version
javac -version
docker-compose config
docker-compose build
docker-compose up -d
docker-compose ps
(cd service && ./mvnw -B test)
```

Затем загрузите `task/sources/Датасет скорректированный.geojson`, запустите строгую задачу с `HEATNET_TERMINAL_POLICY=literal`, скачайте результат и выполните описанные строгий и альтернативный валидаторы. Развёртывание можно считать проверенным только после успешного выполнения этих команд на целевой Ubuntu-машине.
