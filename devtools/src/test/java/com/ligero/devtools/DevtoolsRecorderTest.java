package com.ligero.devtools;

import com.ligero.beans.Beans;
import com.ligero.beans.stereotype.Repository;
import com.ligero.beans.stereotype.Service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DevtoolsRecorderTest {

    interface Repo {
        String find(int id);
    }

    @Repository
    static class StubRepo implements Repo {
        @Override
        public String find(int id) {
            if (id < 0) {
                throw new IllegalArgumentException("negative id");
            }
            return "item-" + id;
        }
    }

    @Service
    static class PlainService {
    }

    @AfterEach
    void clearTrace() {
        DevtoolsRecorder.CURRENT.remove();
    }

    @Test
    void spiesInterfaceBeansIntoTheCurrentTrace() {
        DevtoolsRecorder recorder = new DevtoolsRecorder();
        Beans beans = Beans.builder()
            .bind(Repo.class, b -> new StubRepo())
            .instrument(recorder)
            .start();

        RequestTrace trace = new RequestTrace("t1", "GET", "/items/7");
        DevtoolsRecorder.CURRENT.set(trace);
        String result = beans.get(Repo.class).find(7);
        assertThat(result).isEqualTo("item-7");

        List<RequestTrace.Call> calls = trace.calls();
        assertThat(calls).hasSize(1);
        RequestTrace.Call call = calls.get(0);
        assertThat(call.bean()).isEqualTo("StubRepo");
        assertThat(call.declaredBy()).isEqualTo("Repo");
        assertThat(call.stereotype()).isEqualTo("repository");
        assertThat(call.method()).isEqualTo("find");
        assertThat(call.args()).isEqualTo("[7]");            // JSON array of arguments
        assertThat(call.result()).isEqualTo("\"item-7\"");   // JSON value
        assertThat(call.error()).isNull();
    }

    @Test
    void recordsFailuresAndRethrowsTheOriginalException() {
        DevtoolsRecorder recorder = new DevtoolsRecorder();
        Beans beans = Beans.builder()
            .bind(Repo.class, b -> new StubRepo())
            .instrument(recorder)
            .start();
        Repo repo = beans.get(Repo.class);

        RequestTrace trace = new RequestTrace("t2", "GET", "/items/-1");
        DevtoolsRecorder.CURRENT.set(trace);
        assertThatThrownBy(() -> repo.find(-1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("negative id");
        assertThat(trace.calls().get(0).error())
            .isEqualTo("IllegalArgumentException: negative id");
    }

    @Test
    void outsideARequestTheProxyIsAPassThrough() {
        DevtoolsRecorder recorder = new DevtoolsRecorder();
        Beans beans = Beans.builder()
            .bind(Repo.class, b -> new StubRepo())
            .instrument(recorder)
            .start();
        assertThat(beans.get(Repo.class).find(1)).isEqualTo("item-1");
        // no trace open, nothing recorded, nothing blows up
    }

    @Test
    void concreteClassBeansAreLeftUnwrappedAndListed() {
        DevtoolsRecorder recorder = new DevtoolsRecorder();
        Beans beans = Beans.builder()
            .bind(PlainService.class, b -> new PlainService())
            .instrument(recorder)
            .start();
        assertThat(beans.get(PlainService.class)).isExactlyInstanceOf(PlainService.class);
        assertThat(recorder.unspied()).containsExactly(PlainService.class.getName());
        assertThat(recorder.stereotypes())
            .containsEntry(PlainService.class.getName(), "service");
    }
}
