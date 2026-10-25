package com.ligero.examples.users;

import com.ligero.Ligero;
import com.ligero.LigeroModule;
import com.ligero.beans.Beans;

/**
 * The "users" feature: all of its wiring lives here, so {@code Application}
 * never grows. A missing binding or a cycle fails at startup, never mid-request.
 */
public final class UsersModule implements LigeroModule {

    @Override
    public void beans(Beans.Builder builder) {
        builder
            .bind(UserRepository.class, b -> new InMemoryUserRepository())
            .bind(UserService.class,    b -> new DefaultUserService(b.get(UserRepository.class)))
            .bind(UserController.class, b -> new UserController(b.get(UserService.class)));
    }

    @Override
    public void routes(Ligero app, Beans beans) {
        beans.get(UserController.class).register(app);
    }
}
