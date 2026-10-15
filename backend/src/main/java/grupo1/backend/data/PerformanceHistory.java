package grupo1.backend.data;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;

@Component
public class PerformanceHistory {

    public record Sample(LocalDateTime sampledAt, Double cpuPercent, Double memoryPercent) {
    }

    private static final Duration RETENTION = Duration.ofHours(25);
    private static final Duration MIN_GAP = Duration.ofSeconds(30);

    private final Deque<Sample> samples = new ArrayDeque<>();
    private String profileId;

    public synchronized void add(String owner, Sample sample) {
        if (!Objects.equals(owner, profileId)) {
            samples.clear();
            profileId = owner;
        }
        Sample last = samples.peekLast();
        if (last != null && Duration.between(last.sampledAt(), sample.sampledAt()).compareTo(MIN_GAP) < 0) {
            return;
        }
        samples.addLast(sample);
        LocalDateTime limit = sample.sampledAt().minus(RETENTION);
        while (!samples.isEmpty() && samples.peekFirst().sampledAt().isBefore(limit)) {
            samples.pollFirst();
        }
    }

    public synchronized List<Sample> findSince(String owner, LocalDateTime since) {
        if (!Objects.equals(owner, profileId)) {
            return List.of();
        }
        return samples.stream()
                .filter(sample -> !sample.sampledAt().isBefore(since))
                .toList();
    }
}