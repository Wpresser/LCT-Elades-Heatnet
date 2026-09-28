package ru.lct.heatnet.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Настройки сервиса (префикс {@code app}). */
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Storage storage = new Storage();
    private final Compute compute = new Compute();
    private final Limits limits = new Limits();

    public Storage getStorage() {
        return storage;
    }

    public Compute getCompute() {
        return compute;
    }

    public Limits getLimits() {
        return limits;
    }

    public static class Storage {
        /** Каталог для входных файлов, результатов и временных файлов загрузки. */
        private String dir = "./data";

        public String getDir() {
            return dir;
        }

        public void setDir(String dir) {
            this.dir = dir;
        }
    }

    public static class Compute {
        /** Сколько расчётов идёт одновременно; остальные ждут в очереди. */
        private int maxParallel = 1;
        /** Сколько заданий может ждать в очереди. */
        private int queueCapacity = 100;

        public int getMaxParallel() {
            return maxParallel;
        }

        public void setMaxParallel(int maxParallel) {
            this.maxParallel = maxParallel;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }
    }

    public static class Limits {
        /**
         * Максимум объектов первого прохода (цели, сеть, камеры, источники), которые держим в памяти.
         * При превышении задание завершается понятной ошибкой.
         */
        private int maxCoreObjects = 500_000;
        /** Максимум ограничений, отобранных в окна поиска. */
        private int maxRestrictions = 2_000_000;

        public int getMaxCoreObjects() {
            return maxCoreObjects;
        }

        public void setMaxCoreObjects(int maxCoreObjects) {
            this.maxCoreObjects = maxCoreObjects;
        }

        public int getMaxRestrictions() {
            return maxRestrictions;
        }

        public void setMaxRestrictions(int maxRestrictions) {
            this.maxRestrictions = maxRestrictions;
        }
    }
}
