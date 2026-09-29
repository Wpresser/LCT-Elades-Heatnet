package ru.lct.heatnet.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import ru.lct.heatnet.config.AppProperties;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Приём файлов и запуск расчётов. Файл пишется на диск потоком, в память целиком не читается.
 * Одновременно выполняется не больше {@code app.compute.max-parallel} расчётов.
 */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final JobStore store;
    private final JobProcessor processor;
    private final Path root;
    private final ThreadPoolExecutor executor;

    public JobService(JobStore store, JobProcessor processor, AppProperties props) throws IOException {
        this.store = store;
        this.processor = processor;
        this.root = Paths.get(props.getStorage().getDir()).toAbsolutePath().normalize();
        Files.createDirectories(root.resolve("jobs"));
        Files.createDirectories(root.resolve("upload-tmp"));
        int parallel = Math.max(1, props.getCompute().getMaxParallel());
        AtomicInteger n = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(parallel, parallel, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, props.getCompute().getQueueCapacity())),
                r -> {
                    Thread t = new Thread(r, "compute-" + n.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
        log.info("Хранилище: {}, параллельных расчётов: {}", root, parallel);
        recoverInterrupted();
    }

    /** Задания, прерванные перезапуском (QUEUED/RUNNING в БД), помечаются FAILED, чтобы их можно было запустить снова. */
    private void recoverInterrupted() {
        for (Job job : store.list(100_000)) {
            if (job.getStatus() == JobStatus.QUEUED || job.getStatus() == JobStatus.RUNNING) {
                job.setStatus(JobStatus.FAILED);
                job.setMessage("Расчёт прерван перезапуском сервиса, запустите задание снова");
                touch(job);
                log.warn("Задание {} было прервано перезапуском", job.getId());
            }
        }
    }

    public Job upload(MultipartFile file, boolean autostart) throws IOException {
        Job job = newJob(file.getOriginalFilename());
        Path input = jobDir(job.getId()).resolve("input.geojson");
        // Tomcat уже сбросил тело на диск во временный файл; transferTo переносит его без копии в памяти.
        file.transferTo(input.toFile());
        return registerUpload(job, input, autostart);
    }

    public Job upload(InputStream body, String filename, boolean autostart) throws IOException {
        Job job = newJob(filename);
        Path input = jobDir(job.getId()).resolve("input.geojson");
        try {
            Files.copy(body, input, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(input);
                Files.deleteIfExists(input.getParent());
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
        return registerUpload(job, input, autostart);
    }

    private Job newJob(String filename) throws IOException {
        Job job = new Job();
        job.setId(UUID.randomUUID().toString());
        // колонка original_filename — VARCHAR(1024); хвост с расширением важнее начала
        job.setOriginalFilename(filename != null && filename.length() > 255 ? filename.substring(filename.length() - 255) : filename);
        Instant now = Instant.now();
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        Files.createDirectories(jobDir(job.getId()));
        return job;
    }

    private Job registerUpload(Job job, Path input, boolean autostart) throws IOException {
        job.setInputPath(input.toString());
        job.setInputBytes(Files.size(input));
        job.setStatus(JobStatus.UPLOADED);
        store.save(job);
        log.info("Задание {}: загружено {} байт", job.getId(), job.getInputBytes());
        return autostart ? start(job.getId()) : job;
    }

    public Job start(String id) {
        Job job = get(id).orElseThrow(() -> new JobNotFoundException(id));
        if (job.getStatus() == JobStatus.QUEUED || job.getStatus() == JobStatus.RUNNING) {
            throw new IllegalStateException("Задание уже в работе: " + job.getStatus());
        }
        job.setStatus(JobStatus.QUEUED);
        job.setMessage(null);
        touch(job);
        try {
            executor.execute(() -> run(id));
        } catch (RejectedExecutionException e) {
            job.setStatus(JobStatus.FAILED);
            job.setMessage("Очередь расчётов переполнена, повторите позже");
            touch(job);
        }
        return job;
    }

    private void run(String id) {
        Job job = get(id).orElseThrow(() -> new JobNotFoundException(id));
        job.setStatus(JobStatus.RUNNING);
        touch(job);
        long t0 = System.nanoTime();
        try {
            JobProcessor.Result r = processor.process(Paths.get(job.getInputPath()), jobDir(id));
            job.setStatus(JobStatus.DONE);
            job.setResultPath(r.getResultFile() == null ? null : r.getResultFile().toString());
            job.setMessage(r.getMessage());
            job.setStatsJson(r.getStatsJson());
        } catch (Throwable e) {
            log.error("Задание {} упало", id, e);
            job.setStatus(JobStatus.FAILED);
            job.setMessage(userMessage(e));
        }
        touch(job);
        log.info("Задание {}: {} за {} мс", id, job.getStatus(), (System.nanoTime() - t0) / 1_000_000);
    }

    /** Понятное сообщение об ошибке задания: без имён классов Java там, где причина — входной файл. */
    static String userMessage(Throwable e) {
        if (e instanceof com.fasterxml.jackson.core.JsonProcessingException) {
            com.fasterxml.jackson.core.JsonLocation loc = ((com.fasterxml.jackson.core.JsonProcessingException) e).getLocation();
            return "Файл не является корректным JSON/GeoJSON" + (loc == null ? "" : " (строка " + loc.getLineNr()
                    + ", столбец " + loc.getColumnNr() + ")") + ": " + ((com.fasterxml.jackson.core.JsonProcessingException) e).getOriginalMessage();
        }
        if (e instanceof java.nio.charset.CharacterCodingException || e instanceof java.io.CharConversionException) {
            return "Файл не в кодировке UTF-8: сохраните GeoJSON в UTF-8 и загрузите снова";
        }
        if (e instanceof ru.lct.heatnet.io.InputFormatException) {
            return e.getMessage();
        }
        if (e instanceof OutOfMemoryError) {
            return "Не хватило памяти на расчёт (OutOfMemoryError): увеличьте память контейнера";
        }
        if (e instanceof StackOverflowError) {
            return "Слишком глубокая вложенность геометрии во входном файле";
        }
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    private void touch(Job job) {
        job.setUpdatedAt(Instant.now());
        store.save(job);
    }

    public Optional<Job> get(String id) {
        return store.find(id);
    }

    public List<Job> list(int limit) {
        return store.list(limit);
    }

    public Path jobDir(String id) {
        return root.resolve("jobs").resolve(id);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    public static class JobNotFoundException extends RuntimeException {
        public JobNotFoundException(String id) {
            super("Задание не найдено: " + id);
        }
    }
}
