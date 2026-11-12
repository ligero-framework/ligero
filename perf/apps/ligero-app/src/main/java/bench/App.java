package bench;

import com.ligero.Ligero;
import com.ligero.http.NotFoundException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Products CRUD in Ligero — same spec as the Spring Boot and Javalin apps. */
public final class App {

    public record Product(Long id, String name, double price) {
    }

    private static final Map<Long, Product> STORE = new ConcurrentHashMap<>();
    private static final AtomicLong IDS = new AtomicLong();

    public static void main(String[] args) throws Exception {
        seed();
        Ligero app = Ligero.create(port(args));

        app.get("/products", ctx -> ctx.json(List.copyOf(STORE.values())));

        app.get("/products/{id}", ctx -> {
            Product product = STORE.get(ctx.pathParamAsLong("id"));
            if (product == null) {
                throw new NotFoundException("not found");
            }
            ctx.json(product);
        });

        app.post("/products", ctx -> {
            Product body = ctx.body(Product.class);
            long id = IDS.incrementAndGet();
            Product created = new Product(id, body.name(), body.price());
            STORE.put(id, created);
            ctx.status(201).json(created);
        });

        app.start();
    }

    private static void seed() {
        for (String name : new String[] {"Teclado", "Mouse", "Monitor"}) {
            long id = IDS.incrementAndGet();
            STORE.put(id, new Product(id, name, 19.9 * id));
        }
    }

    private static int port(String[] args) {
        return args.length > 0 ? Integer.parseInt(args[0]) : 8080;
    }
}
