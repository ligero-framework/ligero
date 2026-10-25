package com.ligero.examples.system;

import com.ligero.Ligero;
import com.ligero.beans.stereotype.Controller;

/** HTTP-only endpoints with no domain behind them: a welcome page and an SSE demo. */
@Controller
public final class SystemController {

    public void register(Ligero app) {
        app.get("/", ctx -> ctx.text("Welcome to Ligero!"));

        app.get("/events", ctx -> {
            try (var sse = ctx.sse()) {
                for (int i = 1; i <= 3; i++) {
                    sse.send("tick", String.valueOf(i));
                }
            }
        });
    }
}
