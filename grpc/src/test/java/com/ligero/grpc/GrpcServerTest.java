package com.ligero.grpc;

import com.ligero.Ligero;
import com.ligero.http.NotFoundException;
import com.ligero.test.LigeroTest;

import io.grpc.CallOptions;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A hand-written service with a String codec, so the test needs no protoc. */
class GrpcServerTest {

    private static final MethodDescriptor.Marshaller<String> TEXT = new MethodDescriptor.Marshaller<>() {
        @Override
        public InputStream stream(String value) {
            return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String parse(InputStream stream) {
            try {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    };

    private static final MethodDescriptor<String, String> GREET = MethodDescriptor.<String, String>newBuilder()
        .setType(MethodDescriptor.MethodType.UNARY)
        .setFullMethodName(MethodDescriptor.generateFullMethodName("demo.Greeter", "Greet"))
        .setRequestMarshaller(TEXT).setResponseMarshaller(TEXT).build();

    private final List<String> threads = new CopyOnWriteArrayList<>();
    private ManagedChannel channel;
    private GrpcServer server;
    private LigeroTest http;

    private ServerServiceDefinition greeter() {
        return ServerServiceDefinition.builder("demo.Greeter")
            .addMethod(GREET, ServerCalls.asyncUnaryCall((name, reply) -> {
                threads.add(Thread.currentThread().isVirtual() ? "virtual" : "platform");
                switch (name) {
                    case "ghost" -> throw new NotFoundException("No one called ghost");
                    case "crash" -> throw new IllegalStateException("db password is hunter2");
                    case "denied" -> throw Status.PERMISSION_DENIED.withDescription("no").asRuntimeException();
                    default -> {
                        reply.onNext("Hello, " + name);
                        reply.onCompleted();
                    }
                }
            }))
            .build();
    }

    private ManagedChannel connect(int port) {
        channel = Grpc.newChannelBuilderForAddress("127.0.0.1", port, InsecureChannelCredentials.create()).build();
        return channel;
    }

    @AfterEach
    void stop() throws InterruptedException {
        if (channel != null) {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
        if (http != null) {
            http.close();
        }
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void servesCallsOnVirtualThreadsAndMapsErrors() {
        server = GrpcServer.builder(0).host("127.0.0.1").service(greeter()).build();
        server.start();
        ManagedChannel ch = connect(server.port());

        assertThat(ClientCalls.blockingUnaryCall(ch, GREET, CallOptions.DEFAULT, "Ana")).isEqualTo("Hello, Ana");
        assertThat(threads).containsOnly("virtual");

        assertThatThrownBy(() -> ClientCalls.blockingUnaryCall(ch, GREET, CallOptions.DEFAULT, "ghost"))
            .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
                assertThat(e.getStatus().getDescription()).isEqualTo("No one called ghost");
            });
        assertThatThrownBy(() -> ClientCalls.blockingUnaryCall(ch, GREET, CallOptions.DEFAULT, "crash"))
            .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNKNOWN);
                assertThat(String.valueOf(e.getStatus().getDescription())).doesNotContain("hunter2");
            });
        assertThatThrownBy(() -> ClientCalls.blockingUnaryCall(ch, GREET, CallOptions.DEFAULT, "denied"))
            .isInstanceOfSatisfying(StatusRuntimeException.class,
                e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED));
    }

    @Test
    void oversizedMessagesAreRefused() {
        server = GrpcServer.builder(0).host("127.0.0.1").service(greeter()).maxMessageBytes(64).build();
        server.start();
        ManagedChannel ch = connect(server.port());
        assertThatThrownBy(() -> ClientCalls.blockingUnaryCall(ch, GREET, CallOptions.DEFAULT, "x".repeat(1000)))
            .isInstanceOfSatisfying(StatusRuntimeException.class,
                e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED));
    }

    @Test
    void interceptorsRunAroundCalls() {
        List<String> seen = new CopyOnWriteArrayList<>();
        ServerInterceptor auth = new ServerInterceptor() {
            @Override
            public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                             ServerCallHandler<Q, R> next) {
                String token = headers.get(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER));
                seen.add(call.getMethodDescriptor().getFullMethodName() + " " + token);
                if (token == null) {
                    call.close(Status.UNAUTHENTICATED, new Metadata());
                    return new ServerCall.Listener<>() { };
                }
                return next.startCall(call, headers);
            }
        };
        server = GrpcServer.builder(0).host("127.0.0.1").service(greeter()).interceptor(auth).build();
        server.start();
        ManagedChannel ch = connect(server.port());
        assertThatThrownBy(() -> ClientCalls.blockingUnaryCall(ch, GREET, CallOptions.DEFAULT, "Ana"))
            .isInstanceOfSatisfying(StatusRuntimeException.class,
                e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
        assertThat(seen).containsExactly("demo.Greeter/Greet null");
    }

    @Test
    void healthChecksFollowTheServerAndTheApp() {
        server = GrpcServer.builder(0).host("127.0.0.1").service(greeter()).health().reflection().build();
        Ligero app = Ligero.create(0);
        server.attach(app);
        http = LigeroTest.start(app);
        var health = HealthGrpc.newBlockingStub(connect(server.port()));

        assertThat(health.check(HealthCheckRequest.newBuilder().setService("demo.Greeter").build()).getStatus())
            .isEqualTo(HealthCheckResponse.ServingStatus.SERVING);
        server.setHealth("demo.Greeter", false);
        assertThat(health.check(HealthCheckRequest.newBuilder().setService("demo.Greeter").build()).getStatus())
            .isEqualTo(HealthCheckResponse.ServingStatus.NOT_SERVING);

        http.close(); // stopping the app stops gRPC too
        http = null;
        assertThatThrownBy(server::port).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void statusMappingAndMisuse() {
        assertThat(HttpExceptionInterceptor.statusOf(400).getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(HttpExceptionInterceptor.statusOf(401).getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
        assertThat(HttpExceptionInterceptor.statusOf(409).getCode()).isEqualTo(Status.Code.ABORTED);
        assertThat(HttpExceptionInterceptor.statusOf(429).getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
        assertThat(HttpExceptionInterceptor.statusOf(503).getCode()).isEqualTo(Status.Code.UNAVAILABLE);
        assertThat(HttpExceptionInterceptor.statusOf(500).getCode()).isEqualTo(Status.Code.INTERNAL);
        assertThat(HttpExceptionInterceptor.statusOf(418).getCode()).isEqualTo(Status.Code.UNKNOWN);

        assertThatThrownBy(() -> GrpcServer.builder(0).build()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> GrpcServer.builder(70000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GrpcServer.builder(0).maxMessageBytes(0)).isInstanceOf(IllegalArgumentException.class);
        GrpcServer noHealth = GrpcServer.builder(0).service(greeter()).build();
        assertThatThrownBy(() -> noHealth.setHealth("", true)).isInstanceOf(IllegalStateException.class);
        noHealth.stop(); // not running: nothing to do

        server = GrpcServer.builder(0).host("127.0.0.1").service(greeter()).build();
        server.start();
        assertThatThrownBy(server::start).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void everyLimitCanBeTuned() {
        server = GrpcServer.builder(0).host("127.0.0.1").service(greeter())
            .credentials(io.grpc.InsecureServerCredentials.create())
            .maxMetadataBytes(4096).maxConcurrentCallsPerConnection(10)
            .handshakeTimeout(java.time.Duration.ofSeconds(5)).maxConnectionIdle(java.time.Duration.ofMinutes(1))
            .shutdownGrace(java.time.Duration.ofMillis(100))
            .build();
        server.start();
        assertThat(ClientCalls.blockingUnaryCall(connect(server.port()), GREET, CallOptions.DEFAULT, "Bo"))
            .isEqualTo("Hello, Bo");
        assertThatThrownBy(() -> GrpcServer.builder(0).tls(new java.io.File("missing-cert.pem"),
            new java.io.File("missing-key.pem"))).isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void aPortInUseIsReported() {
        server = GrpcServer.builder(0).host("127.0.0.1").service(greeter()).build();
        server.start();
        GrpcServer second = GrpcServer.builder(server.port()).host("127.0.0.1").service(greeter()).build();
        assertThatThrownBy(second::start).isInstanceOf(UncheckedIOException.class);
    }
}
