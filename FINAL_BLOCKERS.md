# Final blockers

The current Windows agent has only a Java 8 runtime and no JDK 11 compiler, Docker Engine, or docker-compose executable. Java Maven tests, `docker-compose config`, image build, service startup, and API upload/download E2E are therefore **NOT VERIFIED** here.

Run the following on Ubuntu 22 before deployment:

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

Then upload `task/sources/Датасет скорректированный.geojson`, run one strict job with `HEATNET_TERMINAL_POLICY=literal`, download the result, and run the documented strict and alternative validators. No deployment success is claimed until those commands complete on the target Ubuntu host.
