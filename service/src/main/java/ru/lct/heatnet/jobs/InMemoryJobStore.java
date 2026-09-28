package ru.lct.heatnet.jobs;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** Хранение заданий в памяти — для локального запуска без PostgreSQL. */
public class InMemoryJobStore implements JobStore {

    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    @Override
    public void save(Job job) {
        jobs.put(job.getId(), job.copy());
    }

    @Override
    public Optional<Job> find(String id) {
        return Optional.ofNullable(jobs.get(id)).map(Job::copy);
    }

    @Override
    public List<Job> list(int limit) {
        return jobs.values().stream()
                .sorted(Comparator.comparing(Job::getCreatedAt).reversed())
                .limit(limit)
                .map(Job::copy)
                .collect(Collectors.toList());
    }
}
