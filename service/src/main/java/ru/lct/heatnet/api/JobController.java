package ru.lct.heatnet.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.lct.heatnet.jobs.Job;
import ru.lct.heatnet.jobs.JobService;
import ru.lct.heatnet.jobs.JobStatus;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/jobs")
@Tag(name = "Задания", description = "Загрузка GeoJSON, запуск расчёта, статус и выгрузка результата")
public class JobController {

    private final JobService jobs;

    public JobController(JobService jobs) {
        this.jobs = jobs;
    }

    @Operation(summary = "Загрузить входной GeoJSON (multipart)",
            description = "Файл пишется на диск потоком. При autostart=true расчёт сразу ставится в очередь.")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<JobDto> upload(@RequestPart("file") MultipartFile file,
                                         @RequestParam(defaultValue = "true") boolean autostart) throws IOException {
        Job job = jobs.upload(file, autostart);
        return ResponseEntity.status(HttpStatus.CREATED).body(JobDto.of(job));
    }

    @Operation(summary = "Загрузить входной GeoJSON телом запроса",
            description = "Для больших файлов из командной строки: curl --data-binary @file.geojson")
    @PostMapping(path = "/raw", consumes = {MediaType.APPLICATION_OCTET_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE,
            "application/geo+json"})
    public ResponseEntity<JobDto> uploadRaw(HttpServletRequest request,
                                            @RequestParam(defaultValue = "input.geojson") String filename,
                                            @RequestParam(defaultValue = "true") boolean autostart) throws IOException {
        try (InputStream body = request.getInputStream()) {
            Job job = jobs.upload(body, filename, autostart);
            return ResponseEntity.status(HttpStatus.CREATED).body(JobDto.of(job));
        }
    }

    @Operation(summary = "Запустить (или перезапустить) расчёт загруженного файла")
    @PostMapping("/{id}/start")
    public JobDto start(@PathVariable String id) {
        return JobDto.of(jobs.start(id));
    }

    @Operation(summary = "Статус задания")
    @GetMapping("/{id}")
    public JobDto get(@PathVariable String id) {
        return JobDto.of(jobs.get(id).orElseThrow(() -> new JobService.JobNotFoundException(id)));
    }

    @Operation(summary = "Последние задания")
    @GetMapping
    public List<JobDto> list(@Parameter(description = "Сколько заданий вернуть") @RequestParam(defaultValue = "50") int limit) {
        return jobs.list(Math.min(Math.max(limit, 1), 500)).stream().map(JobDto::of).collect(Collectors.toList());
    }

    /** Больше этого входной файл в просмотрщик не отдаём, МБ. */
    static final long VIEWER_INPUT_LIMIT = 20L * 1024 * 1024;

    @Operation(summary = "Входной файл задания (для просмотра на карте, до 20 МБ)")
    @GetMapping("/{id}/input")
    public ResponseEntity<Resource> input(@PathVariable String id) throws IOException {
        Job job = jobs.get(id).orElseThrow(() -> new JobService.JobNotFoundException(id));
        Path file = Paths.get(job.getInputPath());
        long size = Files.size(file);
        if (size > VIEWER_INPUT_LIMIT) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/geo+json"))
                .contentLength(size)
                .body(new FileSystemResource(file));
    }

    @Operation(summary = "Скачать результат (GeoJSON FeatureCollection)")
    @GetMapping("/{id}/result")
    public ResponseEntity<Resource> result(@PathVariable String id) throws IOException {
        Job job = jobs.get(id).orElseThrow(() -> new JobService.JobNotFoundException(id));
        if (job.getStatus() != JobStatus.DONE || job.getResultPath() == null) {
            throw new IllegalStateException("Результат ещё не готов: " + job.getStatus());
        }
        Path file = Paths.get(job.getResultPath());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/geo+json"))
                .contentLength(Files.size(file))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("result_" + id + ".geojson").build().toString())
                .body(new FileSystemResource(file));
    }
}
