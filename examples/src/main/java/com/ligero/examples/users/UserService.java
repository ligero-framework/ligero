package com.ligero.examples.users;

import java.util.List;

/** Business contract. No HTTP types, no storage details. */
public interface UserService {

    List<User> list();

    /** @throws com.ligero.http.NotFoundException if the user does not exist */
    User get(long id);

    User create(String name);

    void delete(long id);
}
