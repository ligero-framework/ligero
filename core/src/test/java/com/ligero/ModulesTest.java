package com.ligero;

import com.ligero.beans.Beans;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModulesTest {

    record Config(String name) {
    }

    static class Repo {
        final Config config;

        Repo(Config config) {
            this.config = config;
        }
    }

    /** Infrastructure module: binds shared beans other modules depend on. */
    static class InfraModule implements LigeroModule {
        @Override
        public void beans(Beans.Builder builder) {
            builder.bindInstance(Config.class, new Config("test"));
        }

        @Override
        public void routes(Ligero app, Beans beans) {
            // no routes
        }
    }

    /** Feature module: depends on a bean bound by InfraModule. */
    static class FeatureModule implements LigeroModule {
        final List<String> events;

        FeatureModule(List<String> events) {
            this.events = events;
        }

        @Override
        public void beans(Beans.Builder builder) {
            builder.bind(Repo.class, b -> new Repo(b.get(Config.class)));
        }

        @Override
        public void routes(Ligero app, Beans beans) {
            events.add("routes:" + beans.get(Repo.class).config.name());
            app.get("/feature", ctx -> ctx.text("ok"));
        }
    }

    @Test
    void assemblesOneContainerFromAllModulesAndMountsRoutes() {
        List<String> events = new ArrayList<>();
        Ligero app = Ligero.create();

        Beans beans = Modules.install(app, new InfraModule(), new FeatureModule(events));

        // cross-module dependency resolved, routes ran with the started container
        assertThat(events).containsExactly("routes:test");
        assertThat(beans.get(Repo.class).config).isSameAs(beans.get(Config.class));
    }

    @Test
    void appliesTheDecoratorToBeansOfEveryModule() {
        List<String> decorated = new ArrayList<>();
        Ligero app = Ligero.create();

        Modules.install(app, new com.ligero.beans.BeanDecorator() {
            @Override
            public <T> T decorate(Class<T> type, T bean) {
                decorated.add(type.getSimpleName());
                return bean;
            }
        }, new InfraModule(), new FeatureModule(new ArrayList<>()));

        assertThat(decorated).containsExactlyInAnyOrder("Config", "Repo");
    }

    @Test
    void duplicateBindingsAcrossModulesFailFast() {
        Ligero app = Ligero.create();
        LigeroModule duplicate = new InfraModule();

        assertThatThrownBy(() -> Modules.install(app, new InfraModule(), duplicate))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Duplicate binding");
    }

    @Test
    void missingCrossModuleDependencyFailsAtInstall() {
        Ligero app = Ligero.create();

        // FeatureModule sin InfraModule: falta Config
        assertThatThrownBy(() -> Modules.install(app, new FeatureModule(new ArrayList<>())))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No binding for");
    }
}
