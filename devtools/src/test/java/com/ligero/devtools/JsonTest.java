package com.ligero.devtools;

import com.ligero.beans.BeanGraph;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonTest {

    @Test
    void escapesStrings() {
        assertThat(Json.str(null)).isEqualTo("null");
        assertThat(Json.str("a\"b\\c\nd")).isEqualTo("\"a\\\"b\\\\c\\nd\"");
        assertThat(Json.str("ctl" + (char) 0x02)).isEqualTo("\"ctl\\u0002\"");
    }

    @Test
    void serializesGraphWithStereotypeOverlayAndUnspied() {
        BeanGraph graph = new BeanGraph(
            List.of(new BeanGraph.Node("com.x.Repo", "bean"),
                    new BeanGraph.Node("com.x.Svc", "service")),
            List.of(new BeanGraph.Edge("com.x.Svc", "com.x.Repo")));
        String json = Json.graph(graph, Map.of("com.x.Repo", "repository"), List.of("com.x.Cfg"));
        assertThat(json).isEqualTo(
            "{\"nodes\":[{\"type\":\"com.x.Repo\",\"stereotype\":\"repository\"},"
            + "{\"type\":\"com.x.Svc\",\"stereotype\":\"service\"}],"
            + "\"edges\":[{\"from\":\"com.x.Svc\",\"to\":\"com.x.Repo\"}],"
            + "\"unspied\":[\"com.x.Cfg\"]}");
    }

    @Test
    void serializesTraceWithCalls() {
        RequestTrace trace = new RequestTrace("abc", "GET", "/items/1");
        RequestTrace.Call call = trace.enter("StubRepo", "Repo", "repository", "find", "1");
        call.complete("item-1", null, 42);
        trace.exit();
        trace.finish(200);

        String json = Json.trace(trace);
        assertThat(json)
            .contains("\"id\":\"abc\"")
            .contains("\"method\":\"GET\"")
            .contains("\"path\":\"/items/1\"")
            .contains("\"status\":200")
            .contains("\"bean\":\"StubRepo\"")
            .contains("\"stereotype\":\"repository\"")
            .contains("\"args\":\"1\"")
            .contains("\"result\":\"item-1\"")
            .contains("\"error\":null")
            .contains("\"durationUs\":42");
        assertThat(Json.traces(List.of(trace))).isEqualTo("[" + json + "]");
    }
}
