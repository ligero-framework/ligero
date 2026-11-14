package bench;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Products CRUD in Spring Boot (Web MVC / embedded Tomcat) — same spec as the others. */
@SpringBootApplication
@RestController
public class App {

    public record Product(Long id, String name, double price) {
    }

    private final Map<Long, Product> store = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    public App() {
        for (String name : new String[] {"Teclado", "Mouse", "Monitor"}) {
            long id = ids.incrementAndGet();
            store.put(id, new Product(id, name, 19.9 * id));
        }
    }

    @GetMapping("/products")
    public List<Product> list() {
        return List.copyOf(store.values());
    }

    @GetMapping("/products/{id}")
    public ResponseEntity<Product> get(@PathVariable("id") long id) {
        Product product = store.get(id);
        return product == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(product);
    }

    @PostMapping("/products")
    public ResponseEntity<Product> create(@RequestBody Product body) {
        long id = ids.incrementAndGet();
        Product created = new Product(id, body.name(), body.price());
        store.put(id, created);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    public static void main(String[] args) {
        if (args.length > 0) {
            System.setProperty("server.port", args[0]);
        }
        System.setProperty("logging.level.root", "WARN");
        SpringApplication.run(App.class, args);
    }
}
