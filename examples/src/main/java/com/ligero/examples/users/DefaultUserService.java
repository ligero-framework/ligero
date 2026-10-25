package com.ligero.examples.users;

import com.ligero.beans.stereotype.Service;
import com.ligero.http.NotFoundException;

import java.util.List;

@Service
public final class DefaultUserService implements UserService {

    private final UserRepository repository;

    public DefaultUserService(UserRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<User> list() {
        return repository.findAll();
    }

    @Override
    public User get(long id) {
        return repository.findById(id)
            .orElseThrow(() -> new NotFoundException("User " + id + " does not exist"));
    }

    @Override
    public User create(String name) {
        return repository.save(name);
    }

    @Override
    public void delete(long id) {
        repository.deleteById(id);
    }
}
