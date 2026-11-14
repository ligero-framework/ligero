package bench;

import io.javalin.Javalin;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Products CRUD in Javalin (Jetty) — same spec as the Ligero and Spring Boot apps. */
public final class App {

    public record Product(Long id, String name, double price) {
    }

    private static final Map<Long, Product> STORE = new ConcurrentHashMap<>();
    private static final AtomicLong IDS = new AtomicLong();

    public static void main(String[] args) {
        seed();
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;

        Javalin app = Javalin.create().start(port);

        app.get("/products", ctx -> ctx.json(List.copyOf(STORE.values())));

        app.get("/products/{id}", ctx -> {
            Product product = STORE.get(Long.parseLong(ctx.pathParam("id")));
            if (product == null) {
                ctx.status(404);
            } else {
                ctx.json(product);
            }
        });

        app.post("/products", ctx -> {
            Product body = ctx.bodyAsClass(Product.class);
            long id = IDS.incrementAndGet();
            Product created = new Product(id, body.name(), body.price());
            STORE.put(id, created);
            ctx.status(201).json(created);
        });
    }

    private static void seed() {
        for (String name : new String[] {"Teclado", "Mouse", "Monitor"}) {
            long id = IDS.incrementAndGet();
            STORE.put(id, new Product(id, name, 19.9 * id));
        }
    }
}
