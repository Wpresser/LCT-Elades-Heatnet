package ru.lct.heatnet.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/** Хранение заданий в PostgreSQL (профиль {@code postgres}). */
public class JdbcJobStore implements JobStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcJobStore.class);

    private static final String DDL = "CREATE TABLE IF NOT EXISTS jobs ("
            + " id VARCHAR(64) PRIMARY KEY,"
            + " status VARCHAR(16) NOT NULL,"
            + " original_filename VARCHAR(1024),"
            + " input_path VARCHAR(2048),"
            + " input_bytes BIGINT,"
            + " result_path VARCHAR(2048),"
            + " message VARCHAR,"
            + " stats_json VARCHAR,"
            + " created_at TIMESTAMP NOT NULL,"
            + " updated_at TIMESTAMP NOT NULL)";

    private static final RowMapper<Job> MAPPER = (rs, i) -> {
        Job j = new Job();
        j.setId(rs.getString("id"));
        j.setStatus(JobStatus.valueOf(rs.getString("status")));
        j.setOriginalFilename(rs.getString("original_filename"));
        j.setInputPath(rs.getString("input_path"));
        j.setInputBytes(rs.getLong("input_bytes"));
        j.setResultPath(rs.getString("result_path"));
        j.setMessage(rs.getString("message"));
        j.setStatsJson(rs.getString("stats_json"));
        j.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        j.setUpdatedAt(rs.getTimestamp("updated_at").toInstant());
        return j;
    };

    private final JdbcTemplate jdbc;

    public JdbcJobStore(JdbcTemplate jdbc, int initAttempts, long initDelayMs) {
        this.jdbc = jdbc;
        createSchema(initAttempts, initDelayMs);
    }

    /** В docker-compose база может подняться позже приложения — повторяем попытки. */
    private void createSchema(int attempts, long delayMs) {
        for (int i = 1; ; i++) {
            try {
                jdbc.execute(DDL);
                return;
            } catch (RuntimeException e) {
                if (i >= attempts) {
                    throw e;
                }
                log.warn("БД недоступна (попытка {}/{}): {}", i, attempts, e.getMessage());
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    @Override
    public void save(Job job) {
        int updated = jdbc.update("UPDATE jobs SET status=?, original_filename=?, input_path=?, input_bytes=?,"
                        + " result_path=?, message=?, stats_json=?, updated_at=? WHERE id=?",
                job.getStatus().name(), job.getOriginalFilename(), job.getInputPath(), job.getInputBytes(),
                job.getResultPath(), job.getMessage(), job.getStatsJson(), Timestamp.from(job.getUpdatedAt()),
                job.getId());
        if (updated == 0) {
            jdbc.update("INSERT INTO jobs (id, status, original_filename, input_path, input_bytes, result_path,"
                            + " message, stats_json, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    job.getId(), job.getStatus().name(), job.getOriginalFilename(), job.getInputPath(),
                    job.getInputBytes(), job.getResultPath(), job.getMessage(), job.getStatsJson(),
                    Timestamp.from(job.getCreatedAt()), Timestamp.from(job.getUpdatedAt()));
        }
    }

    @Override
    public Optional<Job> find(String id) {
        List<Job> rows = jdbc.query("SELECT * FROM jobs WHERE id=?", MAPPER, id);
        return rows.stream().findFirst();
    }

    @Override
    public List<Job> list(int limit) {
        return jdbc.query("SELECT * FROM jobs ORDER BY created_at DESC LIMIT ?", MAPPER, limit);
    }
}
