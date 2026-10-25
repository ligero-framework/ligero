package com.ligero.examples.users;

import java.util.List;
import java.util.Optional;

/** Data-access contract. One implementation per backing store. */
public interface UserRepository {

    List<User> findAll();

    Optional<User> findById(long id);

    User save(String name);

    void deleteById(long id);
}
