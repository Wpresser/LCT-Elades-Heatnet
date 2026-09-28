package ru.lct.heatnet.jobs;

import java.nio.file.Path;

/** Расчёт одного задания: входной GeoJSON → результат в каталоге задания. */
public interface JobProcessor {

    Result process(Path input, Path jobDir) throws Exception;

    final class Result {
        private final Path resultFile;
        private final String message;
        private final String statsJson;

        public Result(Path resultFile, String message, String statsJson) {
            this.resultFile = resultFile;
            this.message = message;
            this.statsJson = statsJson;
        }

        public Path getResultFile() {
            return resultFile;
        }

        public String getMessage() {
            return message;
        }

        public String getStatsJson() {
            return statsJson;
        }
    }
}
