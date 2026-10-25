package com.ligero.examples;

import com.ligero.Ligero;
import com.ligero.LigeroModule;
import com.ligero.Modules;
import com.ligero.beans.Beans;
import com.ligero.devtools.Devtools;
import com.ligero.examples.platform.Platform;
import com.ligero.examples.system.SystemModule;
import com.ligero.examples.users.UsersModule;

import java.io.IOException;

/**
 * Bootstrap only: build the app, start it, stop it on shutdown. There is no
 * wiring and no business code here — cross-cutting concerns live in
 * {@link Platform} and each feature is a {@link LigeroModule}.
 *
 * <p>Run with {@code ./gradlew :examples:run} and try:</p>
 * <pre>
 * curl http://localhost:8080/
 * curl http://localhost:8080/api/v1/users
 * curl http://localhost:8080/api/v1/users/1
 * curl -X POST http://localhost:8080/api/v1/users -d '{"name":"Ada"}'
 * open http://localhost:8080/ligero/dev
 * </pre>
 */
public final class Application {

    private Application() {
    }

    public static void main(String[] args) throws IOException {
        Ligero app = create(8080);
        app.start();
        Runtime.getRuntime().addShutdownHook(new Thread(app::stop));
        System.out.println("Example app running at http://localhost:" + app.port());
    }

    /** Builds the fully wired app without starting it (tests call this with port 0). */
    public static Ligero create(int port) {
        Ligero app = Ligero.create(port);
        Platform.install(app);

        Devtools devtools = Devtools.create();
        Beans beans = Modules.install(app, devtools.recorder(), modules());
        devtools.install(app, beans);
        return app;
    }

    /** The application's features — adding one is adding a line. */
    static LigeroModule[] modules() {
        return new LigeroModule[] {
            new SystemModule(),
            new UsersModule(),
        };
    }
}
