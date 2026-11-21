package bench;

import com.ligero.beans.stereotype.Controller;
import com.ligero.http.NotFoundException;
import com.ligero.web.Body;
import com.ligero.web.Get;
import com.ligero.web.PathParam;
import com.ligero.web.Post;
import com.ligero.web.Status;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Controller("/products")
public class ProductController {

    public record Product(Long id, String name, double price) {
    }

    private final Map<Long, Product> store = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    public ProductController() {
        for (String name : new String[] {"Teclado", "Mouse", "Monitor"}) {
            long id = ids.incrementAndGet();
            store.put(id, new Product(id, name, 19.9 * id));
        }
    }

    @Get
    List<Product> list() {
        return List.copyOf(store.values());
    }

    @Get("/{id}")
    Product get(@PathParam long id) {
        Product product = store.get(id);
        if (product == null) {
            throw new NotFoundException("not found");
        }
        return product;
    }

    @Post
    @Status(201)
    Product create(@Body Product body) {
        long id = ids.incrementAndGet();
        Product created = new Product(id, body.name(), body.price());
        store.put(id, created);
        return created;
    }
}
