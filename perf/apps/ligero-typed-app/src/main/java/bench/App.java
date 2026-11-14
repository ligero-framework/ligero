package bench;

import com.ligero.Ligero;
import com.ligero.Modules;
import com.ligero.generated.GeneratedModules;

/** Products CRUD with typed controllers — same spec as every other app here. */
public final class App {

    public static void main(String[] args) throws Exception {
        Ligero app = Ligero.create(args.length > 0 ? Integer.parseInt(args[0]) : 8080);
        Modules.install(app, GeneratedModules.all());
        app.start();
    }
}
