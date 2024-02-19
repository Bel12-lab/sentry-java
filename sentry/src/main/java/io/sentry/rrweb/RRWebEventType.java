package io.sentry.rrweb;

import io.sentry.ILogger;
import io.sentry.JsonDeserializer;
import io.sentry.JsonObjectReader;
import io.sentry.JsonSerializable;
import io.sentry.ObjectWriter;
import java.io.IOException;
import org.jetbrains.annotations.NotNull;

public enum RRWebEventType implements JsonSerializable {
  DomContentLoaded,
  Load,
  FullSnapshot,
  IncrementalSnapshot,
  Meta,
  Custom,
  Plugin;

  @Override
  public void serialize(@NotNull ObjectWriter writer, @NotNull ILogger logger) throws IOException {
    writer.value(ordinal());
  }

  public static final class Deserializer implements JsonDeserializer<RRWebEventType> {
    @Override
    public @NotNull RRWebEventType deserialize(
        @NotNull JsonObjectReader reader, @NotNull ILogger logger) throws Exception {
      return RRWebEventType.values()[reader.nextInt()];
    }
  }
}
