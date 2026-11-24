package com.ligero.grpc;

import com.ligero.http.HttpException;

import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns exceptions thrown by service code into gRPC statuses: an
 * {@link HttpException} keeps its message and gets the equivalent code; any
 * other exception is logged and reported as {@code UNKNOWN} without detail,
 * so internals never reach the client.
 */
final class HttpExceptionInterceptor implements ServerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(GrpcServer.class);

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                     ServerCallHandler<Q, R> next) {
        ServerCall.Listener<Q> delegate = next.startCall(call, headers);
        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(delegate) {
            @Override
            public void onMessage(Q message) {
                guard(() -> super.onMessage(message));
            }

            @Override
            public void onHalfClose() {
                guard(super::onHalfClose);
            }

            @Override
            public void onReady() {
                guard(super::onReady);
            }

            private void guard(Runnable step) {
                try {
                    step.run();
                } catch (StatusRuntimeException e) {
                    close(e.getStatus(), e.getTrailers());
                } catch (HttpException e) {
                    close(statusOf(e.getStatus()).withDescription(e.getMessage()), null);
                } catch (RuntimeException e) {
                    log.error("gRPC call {} failed", call.getMethodDescriptor().getFullMethodName(), e);
                    close(Status.UNKNOWN, null);
                }
            }

            private void close(Status status, Metadata trailers) {
                try {
                    call.close(status, trailers != null ? trailers : new Metadata());
                } catch (IllegalStateException alreadyClosed) {
                    // the service closed the call before failing
                }
            }
        };
    }

    /** The gRPC code that means the same as an HTTP status (per the gRPC HTTP mapping). */
    static Status statusOf(int httpStatus) {
        return switch (httpStatus) {
            case 400, 422 -> Status.INVALID_ARGUMENT;
            case 401 -> Status.UNAUTHENTICATED;
            case 403 -> Status.PERMISSION_DENIED;
            case 404 -> Status.NOT_FOUND;
            case 408, 504 -> Status.DEADLINE_EXCEEDED;
            case 409 -> Status.ABORTED;
            case 412 -> Status.FAILED_PRECONDITION;
            case 413, 429 -> Status.RESOURCE_EXHAUSTED;
            case 501 -> Status.UNIMPLEMENTED;
            case 502, 503 -> Status.UNAVAILABLE;
            default -> httpStatus >= 500 ? Status.INTERNAL : Status.UNKNOWN;
        };
    }
}
