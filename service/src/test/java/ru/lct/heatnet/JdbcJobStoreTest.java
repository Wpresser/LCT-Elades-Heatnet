package ru.lct.heatnet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.lct.heatnet.jobs.JdbcJobStore;
import ru.lct.heatnet.jobs.Job;
import ru.lct.heatnet.jobs.JobStatus;
import ru.lct.heatnet.jobs.JobStore;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Профиль postgres поверх H2 в режиме PostgreSQL: проверяем конфигурацию и SQL хранилища заданий. */
@SpringBootTest(properties = {
        "spring.profiles.active=postgres",
        "spring.datasource.url=jdbc:h2:mem:jobs;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.storage.dir=target/test-data"
})
class JdbcJobStoreTest {

    @Autowired
    JobStore store;

    @Test
    void savesAndReadsJobs() {
        assertThat(store).isInstanceOf(JdbcJobStore.class);

        Job j = new Job();
        j.setId("job-1");
        j.setStatus(JobStatus.UPLOADED);
        j.setOriginalFilename("a.geojson");
        j.setInputPath("/tmp/a");
        j.setInputBytes(42);
        j.setCreatedAt(Instant.parse("2026-09-25T20:00:00Z"));
        j.setUpdatedAt(j.getCreatedAt());
        store.save(j);

        j.setStatus(JobStatus.DONE);
        j.setMessage("готово");
        j.setStatsJson("{\"a\":1}");
        store.save(j);

        Job read = store.find("job-1").orElseThrow(AssertionError::new);
        assertThat(read.getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(read.getMessage()).isEqualTo("готово");
        assertThat(read.getStatsJson()).isEqualTo("{\"a\":1}");
        assertThat(read.getInputBytes()).isEqualTo(42);
        assertThat(store.list(10)).extracting(Job::getId).contains("job-1");
    }
}
