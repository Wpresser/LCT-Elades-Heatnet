package ru.lct.heatnet.jobs;

import java.util.List;
import java.util.Optional;

/** Хранилище состояний заданий. */
public interface JobStore {

    void save(Job job);

    Optional<Job> find(String id);

    /** Последние задания, новые первыми. */
    List<Job> list(int limit);
}
