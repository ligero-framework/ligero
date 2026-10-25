package com.ligero.examples.users;

import com.ligero.beans.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Repository
public final class InMemoryUserRepository implements UserRepository {

    private final Map<Long, User> users = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    public InMemoryUserRepository() {
        save("Ada Lovelace");
    }

    @Override
    public List<User> findAll() {
        return List.copyOf(users.values());
    }

    @Override
    public Optional<User> findById(long id) {
        return Optional.ofNullable(users.get(id));
    }

    @Override
    public User save(String name) {
        long id = ids.incrementAndGet();
        User user = new User(id, name);
        users.put(id, user);
        return user;
    }

    @Override
    public void deleteById(long id) {
        users.remove(id);
    }
}
