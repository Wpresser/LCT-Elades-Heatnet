package ru.lct.heatnet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.lct.heatnet.config.AppProperties;
import ru.lct.heatnet.jobs.*;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

class JobRecoveryTest {
    private AppProperties props(Path root) {
        AppProperties p = new AppProperties();
        p.getStorage().setDir(root.toString());
        p.getCompute().setQueueCapacity(1);
        return p;
    }

    @Test
    void queueOverflowFailsClearlyAndFilenameCannotEscapeStorage(@TempDir Path root) throws Exception {
        InMemoryJobStore store = new InMemoryJobStore();
        CountDownLatch running = new CountDownLatch(1), finish = new CountDownLatch(1);
        JobService service = new JobService(store, (input, directory) -> {
            running.countDown();
            finish.await(10, TimeUnit.SECONDS);
            return new JobProcessor.Result(null, "test", "{}");
        }, props(root));
        try {
            Job first = service.upload(new ByteArrayInputStream(new byte[]{1}), "../../escaped.geojson", true);
            assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(Path.of(first.getInputPath()).normalize()).startsWith(root);
            assertThat(Files.exists(root.resolve("escaped.geojson"))).isFalse();
            service.upload(new ByteArrayInputStream(new byte[]{1}), "queued.geojson", true);
            Job rejected = service.upload(new ByteArrayInputStream(new byte[]{1}), "overflow.geojson", true);
            assertThat(rejected.getStatus()).isEqualTo(JobStatus.FAILED);
            assertThat(rejected.getMessage()).contains("переполнена");
        } finally {
            finish.countDown();
            service.shutdown();
        }
    }

    @Test
    void interruptedQueuedAndRunningJobsBecomeRestartable(@TempDir Path root) throws Exception {
        InMemoryJobStore store = new InMemoryJobStore();
        for (JobStatus status : new JobStatus[]{JobStatus.QUEUED, JobStatus.RUNNING}) {
            Job job = new Job();
            job.setId(status.name());
            job.setStatus(status);
            job.setCreatedAt(Instant.now());
            job.setUpdatedAt(job.getCreatedAt());
            store.save(job);
        }
        JobService service = new JobService(store, (input, directory) -> null, props(root));
        try {
            for (Job job : store.list(10)) {
                assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
                assertThat(job.getMessage()).contains("перезапуском");
            }
        } finally {
            service.shutdown();
        }
    }
}
