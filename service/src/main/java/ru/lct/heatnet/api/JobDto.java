package ru.lct.heatnet.api;

import com.fasterxml.jackson.annotation.JsonRawValue;
import ru.lct.heatnet.jobs.Job;
import ru.lct.heatnet.jobs.JobStatus;

import java.time.Instant;

/** Состояние задания для API. */
public class JobDto {

    public String id;
    public JobStatus status;
    public String originalFilename;
    public long inputBytes;
    public boolean resultReady;
    public String message;
    @JsonRawValue
    public String stats;
    public Instant createdAt;
    public Instant updatedAt;

    static JobDto of(Job j) {
        JobDto d = new JobDto();
        d.id = j.getId();
        d.status = j.getStatus();
        d.originalFilename = j.getOriginalFilename();
        d.inputBytes = j.getInputBytes();
        d.resultReady = j.getStatus() == JobStatus.DONE && j.getResultPath() != null;
        d.message = j.getMessage();
        d.stats = j.getStatsJson();
        d.createdAt = j.getCreatedAt();
        d.updatedAt = j.getUpdatedAt();
        return d;
    }
}
