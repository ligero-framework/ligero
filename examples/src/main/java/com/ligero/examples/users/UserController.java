package com.ligero.examples.users;

import com.ligero.Ligero;
import com.ligero.beans.stereotype.Controller;

/** HTTP only: parse, validate, pick the status code. Business rules live in the service. */
@Controller
public final class UserController {

    record CreateUserRequest(String name) {
    }

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    public void register(Ligero app) {
        app.group("/api/v1/users", api -> {
            api.get("", ctx -> ctx.json(service.list()));

            api.get("/{id}", ctx -> ctx.json(service.get(ctx.pathParamAsLong("id"))));

            api.post("", ctx -> {
                CreateUserRequest request = ctx.bodyValidator(CreateUserRequest.class)
                    .check(r -> r.name() != null && !r.name().isBlank(), "name is required")
                    .get();
                User created = service.create(request.name());
                ctx.status(201).header("Location", "/api/v1/users/" + created.id()).json(created);
            });

            api.delete("/{id}", ctx -> {
                service.delete(ctx.pathParamAsLong("id"));
                ctx.status(204).res().end();
            });
        });
    }
}
