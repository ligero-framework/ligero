package com.ligero.beans;

import com.ligero.beans.stereotype.Controller;
import com.ligero.beans.stereotype.Repository;
import com.ligero.beans.stereotype.Service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BeansTest {

    interface Repo {
        String data();
    }

    @Repository
    static class InMemoryRepo implements Repo {
        @Override
        public String data() {
            return "data";
        }
    }

    @Service
    static class GreetingService {
        final Repo repo;

        GreetingService(Repo repo) {
            this.repo = repo;
        }
    }

    @Controller
    static class GreetingController {
        final GreetingService service;

        GreetingController(GreetingService service) {
            this.service = service;
        }
    }

    private static Beans.Builder wired() {
        return Beans.builder()
            .bind(Repo.class, b -> new InMemoryRepo())
            .bind(GreetingService.class, b -> new GreetingService(b.get(Repo.class)))
            .bind(GreetingController.class, b -> new GreetingController(b.get(GreetingService.class)));
    }

    @Test
    void resolvesGraphWithMemoizedSingletons() {
        Beans beans = wired().start();
        GreetingController controller = beans.get(GreetingController.class);
        assertThat(controller.service).isSameAs(beans.get(GreetingService.class));
        assertThat(controller.service.repo).isSameAs(beans.get(Repo.class));
    }

    @Test
    void lazyContainerOnlyBuildsWhatIsAsked() {
        AtomicInteger built = new AtomicInteger();
        Beans beans = Beans.builder()
            .bind(Repo.class, b -> {
                built.incrementAndGet();
                return new InMemoryRepo();
            })
            .buildLazy();
        assertThat(built.get()).isZero();
        beans.get(Repo.class);
        beans.get(Repo.class);
        assertThat(built.get()).isEqualTo(1);
    }

    @Test
    void startFailsFastOnMissingBinding() {
        assertThatThrownBy(() -> Beans.builder()
                .bind(GreetingService.class, b -> new GreetingService(b.get(Repo.class)))
                .start())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No binding for")
            .hasMessageContaining("Repo")
            .hasMessageContaining("needed by");
    }

    @Test
    void cyclesAreReportedWithTheFullChain() {
        class A {
        }
        class B {
        }
        assertThatThrownBy(() -> Beans.builder()
                .bind(A.class, b -> {
                    b.get(B.class);
                    return new A();
                })
                .bind(B.class, b -> {
                    b.get(A.class);
                    return new B();
                })
                .start())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Dependency cycle")
            .hasMessageContaining("A -> B -> A");
    }

    @Test
    void duplicateBindingsAreRejected() {
        assertThatThrownBy(() -> Beans.builder()
                .bind(Repo.class, b -> new InMemoryRepo())
                .bind(Repo.class, b -> new InMemoryRepo()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Duplicate binding");
    }

    @Test
    void allReturnsBeansAssignableToSupertype() {
        Beans beans = wired().start();
        List<Repo> repos = beans.all(Repo.class);
        assertThat(repos).hasSize(1);
        assertThat(repos.get(0)).isInstanceOf(InMemoryRepo.class);
    }

    @Test
    void graphCapturesRealEdgesAndStereotypes() {
        Beans beans = wired().start();
        BeanGraph graph = beans.graph();

        // Repo is bound as an interface without annotation: the stereotype
        // comes from the instantiated class (@Repository InMemoryRepo).
        assertThat(graph.nodes()).extracting(BeanGraph.Node::stereotype)
            .containsExactlyInAnyOrder("repository", "service", "controller");
        assertThat(graph.edges()).contains(
            new BeanGraph.Edge(GreetingService.class.getName(), Repo.class.getName()),
            new BeanGraph.Edge(GreetingController.class.getName(), GreetingService.class.getName()));
    }

    @Test
    void graphBeforeInstantiationFallsBackToBindingTypeStereotype() {
        Beans beans = wired().buildLazy();
        assertThat(beans.graph().nodes()).extracting(BeanGraph.Node::stereotype)
            .containsExactlyInAnyOrder("bean", "service", "controller");
    }

    @Test
    void decoratorWrapsEveryBean() {
        List<String> decorated = new java.util.ArrayList<>();
        Beans beans = wired()
            .instrument(new BeanDecorator() {
                @Override
                public <T> T decorate(Class<T> type, T bean) {
                    decorated.add(type.getSimpleName());
                    return bean;
                }
            })
            .start();
        assertThat(beans.get(GreetingController.class)).isNotNull();
        assertThat(decorated).containsExactlyInAnyOrder("Repo", "GreetingService", "GreetingController");
    }

    @Test
    void closeClosesAutoCloseablesInReverseOrder() {
        List<String> closed = new java.util.ArrayList<>();
        class Res implements AutoCloseable {
            final String name;

            Res(String name) {
                this.name = name;
            }

            @Override
            public void close() {
                closed.add(name);
            }
        }
        class First extends Res {
            First() {
                super("first");
            }
        }
        class Second extends Res {
            Second() {
                super("second");
            }
        }
        Beans beans = Beans.builder()
            .bind(First.class, b -> new First())
            .bind(Second.class, b -> new Second())
            .start();
        beans.close();
        assertThat(closed).containsExactly("second", "first");
    }
}
