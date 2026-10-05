package com.sfbank.bayanati.spike;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Disk-backed capture store. Everything a recheck or purge needs is read back from disk so a
 * backend or app restart mid-spike loses nothing. {@code spike.log} is append-only JSON lines and
 * never receives a token, a JWS, a decoded value or image bytes -- those live only in the
 * per-session files under {@code target/}.
 */
class UqudoSpikeStore {

  static final JsonMapper MAPPER = JsonMapper.builder().build();

  private final Path root;

  UqudoSpikeStore(Path root) {
    this.root = root;
    try {
      Files.createDirectories(root.resolve("sessions"));
      Files.createDirectories(root.resolve("faces"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  Path root() {
    return root;
  }

  Path sessionDir(String sessionId) {
    return dir(root.resolve("sessions").resolve(safe(sessionId)));
  }

  Path faceDir(String faceSessionId) {
    return dir(root.resolve("faces").resolve(safe(faceSessionId)));
  }

  /** Appends one JSON line to spike.log; {@code endpoint} and {@code ts} are always present. */
  synchronized void log(String endpoint, String sessionId, Map<String, Object> fields) {
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("ts", Instant.now().toString());
    line.put("endpoint", endpoint);
    line.put("sessionId", sessionId);
    line.putAll(fields);
    append(root.resolve("spike.log"), MAPPER.writeValueAsString(line) + "\n");
  }

  void writeJson(Path file, Object value) {
    write(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value));
  }

  void write(Path file, String text) {
    try {
      Files.writeString(file, text, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  void writeBytes(Path file, byte[] bytes) {
    try {
      Files.write(file, bytes);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  Optional<String> read(Path file) {
    try {
      return Files.exists(file) ? Optional.of(Files.readString(file)) : Optional.empty();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  Optional<byte[]> readBytes(Path file) {
    try {
      return Files.exists(file) ? Optional.of(Files.readAllBytes(file)) : Optional.empty();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  Optional<JsonNode> readJson(Path file) {
    return read(file).map(MAPPER::readTree);
  }

  /** Finds the session/face directory whose session.json records the given jti. */
  Optional<Path> findByJti(String jti) {
    for (String kind : new String[] {"sessions", "faces"}) {
      try (Stream<Path> dirs = Files.list(root.resolve(kind))) {
        for (Path d : dirs.toList()) {
          Optional<JsonNode> meta = readJson(d.resolve("session.json"));
          if (meta.isPresent() && jti.equals(meta.get().path("jti").asString(null))) {
            return Optional.of(d);
          }
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return Optional.empty();
  }

  private void append(Path file, String text) {
    try {
      Files.writeString(
          file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Path dir(Path p) {
    try {
      Files.createDirectories(p);
      return p;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String safe(String id) {
    if (id == null || !id.matches("[A-Za-z0-9._-]{1,128}")) {
      throw new IllegalArgumentException("unsafe id");
    }
    return id;
  }
}
