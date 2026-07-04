package com.ligero.json;

import com.ligero.http.BadRequestException;
import com.ligero.spi.BodyMapper;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JacksonBodyMapperTest {

    record User(String name, int age) {
    }

    private final JacksonBodyMapper mapper = new JacksonBodyMapper();

    @Test
    void roundTripsRecords() {
        String json = mapper.writeJson(new User("Ada", 36));
        User back = mapper.readJson(json, User.class);
        assertThat(back).isEqualTo(new User("Ada", 36));
    }

    @Test
    void writesMaps() {
        assertThat(mapper.writeJson(Map.of("k", "v"))).isEqualTo("{\"k\":\"v\"}");
    }

    @Test
    void invalidJsonMapsToBadRequest() {
        assertThatThrownBy(() -> mapper.readJson("{not json", User.class))
            .isInstanceOf(BadRequestException.class)
            .satisfies(e -> org.assertj.core.api.Assertions
                .assertThat(((BadRequestException) e).getStatus()).isEqualTo(400));
    }

    @Test
    void isDiscoverableViaServiceLoader() {
        assertThat(ServiceLoader.load(BodyMapper.class).stream()
                .map(p -> p.type().getName()))
            .contains(JacksonBodyMapper.class.getName());
    }
}
