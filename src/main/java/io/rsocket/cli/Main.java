/*
 * Copyright 2015 Netflix, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.rsocket.cli;

import com.google.common.base.Charsets;
import com.google.common.base.Throwables;
import com.google.common.io.CharSource;
import com.google.common.io.Files;
import io.airlift.airline.*;
import io.rsocket.*;
import io.rsocket.cli.util.LoggingUtil;
import io.rsocket.uri.UriTransportRegistry;
import io.rsocket.util.PayloadImpl;
import org.reactivestreams.Publisher;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;

import static io.rsocket.cli.TimeUtil.parseShortDuration;

/**
 * Simple command line tool to make a RSocket connection and send/receive elements.
 * <p>
 * Currently limited in features, only supports a text/line based approach.
 */
@SuppressWarnings({"WeakerAccess", "CanBeFinal", "unused"})
@Command(name = Main.NAME, description = "CLI for RSocket.")
public class Main {
  static final String NAME = "reactivesocket-cli";

  @Option(name = {"-h", "--help"}, description = "Display help information")
  public boolean help;

  @Option(name = {"-H", "--header"}, description = "Custom header to pass to server")
  public List<String> headers;

  @Option(name = "--str", description = "Request Stream")
  public boolean stream;

  @Option(name = "--rr", description = "Request Response")
  public boolean requestResponse;

  @Option(name = "--fnf", description = "Fire and Forget")
  public boolean fireAndForget;

  @Option(name = "--channel", description = "Channel")
  public boolean channel;

  @Option(name = "--metadataPush", description = "Metadata Push")
  public boolean metadataPush;

  @Option(name = "--server", description = "Start server instead of client")
  public boolean serverMode;

  @Option(name = {"-i", "--input"}, description = "String input or @path/to/file")
  public String input;

  @Option(name = {"-m", "--metadata"}, description = "Metadata input string input or @path/to/file")
  public String metadata;

  @Option(name = {"--metadataFormat"}, description = "Metadata Format", allowedValues = {"json",
          "cbor", "mime-type"})
  public String metadataFormat = "json";

  @Option(name = {"--dataFormat"}, description = "Data Format", allowedValues = {"json", "cbor",
          "mime-type"})
  public String dataFormat = "binary";

  @Option(name = "--setup", description = "String input or @path/to/file for setup metadata")
  public String setup;

  @Option(name = "--debug", description = "Debug Output")
  public boolean debug;

  @Option(name = "--ops", description = "Operation Count")
  public int operations = 1;

  @Option(name = "--timeout", description = "Timeout in seconds")
  public Long timeout;

  @Option(name = "--keepalive", description = "Keepalive period")
  public String keepalive;

  @Arguments(title = "target", description = "Endpoint URL", required = true)
  public List<String> arguments = new ArrayList<>();

  public RSocket client;

  public OutputHandler outputHandler;

  private Closeable server;

  public void run() {
    LoggingUtil.configureLogging(debug);

    if (outputHandler == null) {
      outputHandler = new ConsoleOutputHandler();
    }

    try {
      String uri = arguments.get(0);

      if (serverMode) {
        server = RSocketFactory.receive().acceptor((setupPayload, reactiveSocket) -> createServerRequestHandler(setupPayload))
                .transport(UriTransportRegistry.serverForUri(uri)).start().block();

        server.onClose().block();
      } else {
        RSocketFactory.ClientRSocketFactory clientRSocketFactory = RSocketFactory.connect();

        if (keepalive != null) {
          clientRSocketFactory.keepAliveTickPeriod(parseShortDuration(keepalive));
        }
        clientRSocketFactory.errorConsumer(t -> outputHandler.error("client error", t));
        clientRSocketFactory.metadataMimeType(standardMimeType(metadataFormat));
        clientRSocketFactory.dataMineType(standardMimeType(dataFormat));
        if (setup != null) {
          clientRSocketFactory.setupPayload(parseSetupPayload());
        }

        client = clientRSocketFactory
                .transport(UriTransportRegistry.clientForUri(uri)).start().block();

        Flux<Void> run = run(client);

        if (timeout != null) {
          run.blockLast(Duration.ofSeconds(timeout));
        } else {
          run.blockLast();
        }
      }
    } catch (Exception e) {
      handleError(e);
    }
  }

  private String standardMimeType(String dataFormat) {
    if (dataFormat == null) {
      return "application/json";
    }

    switch (dataFormat) {
      case "json":
        return "application/json";
      case "cbor":
        return "application/cbor";
      case "binary":
        return "application/binary";
      case "text":
        return "text/plain";
      default:
        return "application/json";
    }
  }

  public Payload parseSetupPayload() {
    String source = null;

    if (setup.startsWith("@")) {
      try {
        source = Files.asCharSource(setupFile(), Charsets.UTF_8).read();
      } catch (IOException e) {
        throw Throwables.propagate(e);
      }
    } else {
      source = setup;
    }

    return new PayloadImpl(source);
  }

  private File setupFile() {
    File file = new File(input.substring(1));

    if (!file.isFile()) {
      throw new UsageException("setup file not found: " + file);
    }

    return file;
  }

  public Mono<RSocket> createServerRequestHandler(ConnectionSetupPayload setupPayload) {
    LoggerFactory.getLogger(Main.class).debug("setup payload " + setupPayload);

    return Mono.just(new AbstractRSocket() {
      @Override
      public Mono<Void> fireAndForget(Payload payload) {
        showPayload(payload);
        return Mono.empty();
      }

      @Override
      public Mono<Payload> requestResponse(Payload payload) {
        return handleIncomingPayload(payload).single();
      }

      @Override
      public Flux<Payload> requestStream(Payload payload) {
        return handleIncomingPayload(payload);
      }

      @Override
      public Flux<Payload> requestChannel(Publisher<Payload> payloads) {
        Flux.from(payloads)
                .subscribe(p -> showPayload(p), e -> outputHandler.error("channel error", e));
        return inputPublisher();
      }

      @Override
      public Mono<Void> metadataPush(Payload payload) {
        outputHandler.showOutput(toUtf8String(payload.getMetadata()));
        return Mono.empty();
      }
    });
  }

  private Flux<Payload> handleIncomingPayload(Payload payload) {
    showPayload(payload);
    return inputPublisher();
  }

  private void showPayload(Payload payload) {
    outputHandler.showOutput(toUtf8String(payload.getData()));
  }

  private String toUtf8String(ByteBuffer data) {
    return StandardCharsets.UTF_8.decode(data).toString();
  }

  public Flux<Void> run(RSocket client) {
    try {
      return runAllOperations(client);
    } catch (Exception e) {
      handleError(e);
    }

    return Flux.empty();
  }

  private void handleError(Throwable e) {
    outputHandler.error("error", e);
  }

  private Flux<Void> runAllOperations(RSocket client) {
    return Flux.range(0, operations).flatMap(i -> runSingleOperation(client));
  }

  private Flux<Void> runSingleOperation(RSocket client) {
    try {
      Flux<Payload> source;

      if (fireAndForget) {
        source = client.fireAndForget(singleInputPayload()).thenMany(Flux.empty());
      } else if (metadataPush) {
        source = client.metadataPush(singleInputPayload()).thenMany(Flux.empty());
      } else if (requestResponse) {
        source = client.requestResponse(singleInputPayload()).flux();
      } else if (stream) {
        source = client.requestStream(singleInputPayload());
      } else if (channel) {
        if (input == null) {
          outputHandler.info("Type commands to send to the server.");
        }
        source = client.requestChannel(inputPublisher());
      } else {
        outputHandler.info(
                "Using passive client mode, choose an option to use a different mode.");
        source = Flux.never();
      }

      return source.map(Payload::getData)
              .map(this::toUtf8String)
              .doOnNext(outputHandler::showOutput)
              .doOnError(e -> outputHandler.error("error from server", e))
              .onErrorResume(e -> Flux.empty()).thenMany(Flux.empty());
    } catch (Exception ex) {
      return Flux.<Void>error(ex).doOnError(e -> outputHandler.error("error before query", e)).onErrorResume(e -> Flux.empty());
    }
  }

  private Flux<Payload> inputPublisher() {
    CharSource is;

    if (input == null) {
      is = SystemInCharSource.INSTANCE;
    } else if (input.startsWith("@")) {
      is = Files.asCharSource(inputFile(input), Charsets.UTF_8);
    } else {
      is = CharSource.wrap(input);
    }

    byte[] metadata = buildMetadata();

    return Publishers.lines(is, l -> metadata);
  }

  private static String getInputFromSource(String source, Supplier<String> nullHandler) {
    String s;

    if (source == null) {
      s = nullHandler.get();
    } else if (source.startsWith("@")) {
      try {
        s = Files.toString(inputFile(source), StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new UsageException(e.toString());
      }
    } else {
      s = source;
    }

    return s;
  }

  private static File inputFile(String path) {
    File file = new File(path.substring(1));

    if (!file.isFile()) {
      throw new UsageException("file not found: " + file);
    }

    return file;
  }

  private PayloadImpl singleInputPayload() {
    String data = getInputFromSource(input, () -> {
      Scanner in = new Scanner(System.in);
      return in.nextLine();
    });

    byte[] metadata = buildMetadata();

    return new PayloadImpl(data.getBytes(StandardCharsets.UTF_8), metadata);
  }

  private byte[] buildMetadata() {
    if (this.metadata != null) {
      if (this.headers != null) {
        throw new UsageException("Can't specify headers and metadata");
      }

      return getInputFromSource(this.metadata, () -> "").getBytes(StandardCharsets.UTF_8);
    } else if (this.headers != null) {
      Map<String, String> headerMap = new LinkedHashMap<>();

      if (headers != null) {
        for (String header : headers) {
          String[] parts = header.split(":", 2);
          // TODO: consider better strategy than simple trim
          headerMap.put(parts[0].trim(), parts[1].trim());
        }
      }

      return MetadataUtil.encodeMetadataMap(headerMap, standardMimeType(metadataFormat));
    } else {
      return new byte[0];
    }
  }

  private static Main fromArgs(String... args) {
    SingleCommand<Main> cmd = SingleCommand.singleCommand(Main.class);
    try {
      return cmd.parse(args);
    } catch (ParseException e) {
      System.err.println(e.getMessage());
      Help.help(cmd.getCommandMetadata());
      System.exit(-1);
      return null;
    }
  }

  @SuppressWarnings("ConstantConditions")
  public static void main(String... args) throws Exception {
    fromArgs(args).run();
  }
}