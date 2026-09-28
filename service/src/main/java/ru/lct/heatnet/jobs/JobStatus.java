package ru.lct.heatnet.jobs;

public enum JobStatus {
    /** Файл загружен, расчёт не запускался. */
    UPLOADED,
    /** Ждёт свободного расчётного потока. */
    QUEUED,
    RUNNING,
    DONE,
    FAILED
}
