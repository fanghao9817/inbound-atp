package com.haoyu.inboundsim;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** The container healthcheck is "heartbeat file touched in the last 2 minutes": a stuck simulator turns unhealthy. */
@Component
public class Heartbeat {

    private final Path file;

    public Heartbeat(SimProperties props) {
        this.file = Path.of(props.heartbeatFile());
    }

    public void beat() {
        try {
            if (!Files.exists(file)) Files.createFile(file);
            Files.setLastModifiedTime(file, FileTime.from(Instant.now()));
        } catch (IOException ignored) {
            // the healthcheck will notice
        }
    }
}
