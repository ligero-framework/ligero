package com.ligero.jpa;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the Jpa helper against a real Hibernate provider on in-memory H2,
 * so it validates transactions, reads, and rollback on error end to end.
 */
class JpaTest {

    private static Jpa jpa;

    @BeforeAll
    static void boot() {
        jpa = Jpa.forUnit("test");
    }

    @AfterAll
    static void shutdown() {
        jpa.close();
    }

    private List<TodoEntity> all() {
        return jpa.read(em -> em.createQuery("select t from TodoEntity t", TodoEntity.class).getResultList());
    }

    @Test
    void persistsInATransactionAndReadsBack() {
        TodoEntity saved = jpa.tx(em -> {
            TodoEntity todo = new TodoEntity("buy milk");
            em.persist(todo);
            return todo;
        });
        assertThat(saved.getId()).isNotNull();

        List<TodoEntity> todos = all();
        assertThat(todos).extracting(TodoEntity::getTitle).contains("buy milk");
    }

    @Test
    void rollsBackOnError() {
        long before = all().size();
        java.util.function.Function<jakarta.persistence.EntityManager, Object> doomed = em -> {
            em.persist(new TodoEntity("doomed"));
            throw new IllegalStateException("boom");
        };
        assertThatThrownBy(() -> jpa.tx(doomed)).isInstanceOf(IllegalStateException.class);

        // the failed unit of work left nothing behind
        assertThat(all()).hasSize((int) before)
            .extracting(TodoEntity::getTitle).doesNotContain("doomed");
    }
}
