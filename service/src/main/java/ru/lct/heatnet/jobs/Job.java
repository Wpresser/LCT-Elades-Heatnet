package ru.lct.heatnet.jobs;

import java.time.Instant;

/** Задание на расчёт: один загруженный файл и его результат. */
public class Job {

    private String id;
    private JobStatus status;
    private String originalFilename;
    private String inputPath;
    private long inputBytes;
    private String resultPath;
    /** Текст ошибки или краткий итог расчёта. */
    private String message;
    /** Сводка расчёта в JSON (счётчики, предупреждения, варианты). */
    private String statsJson;
    private Instant createdAt;
    private Instant updatedAt;

    public Job copy() {
        Job c = new Job();
        c.id = id;
        c.status = status;
        c.originalFilename = originalFilename;
        c.inputPath = inputPath;
        c.inputBytes = inputBytes;
        c.resultPath = resultPath;
        c.message = message;
        c.statsJson = statsJson;
        c.createdAt = createdAt;
        c.updatedAt = updatedAt;
        return c;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public JobStatus getStatus() {
        return status;
    }

    public void setStatus(JobStatus status) {
        this.status = status;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }

    public String getInputPath() {
        return inputPath;
    }

    public void setInputPath(String inputPath) {
        this.inputPath = inputPath;
    }

    public long getInputBytes() {
        return inputBytes;
    }

    public void setInputBytes(long inputBytes) {
        this.inputBytes = inputBytes;
    }

    public String getResultPath() {
        return resultPath;
    }

    public void setResultPath(String resultPath) {
        this.resultPath = resultPath;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getStatsJson() {
        return statsJson;
    }

    public void setStatsJson(String statsJson) {
        this.statsJson = statsJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
