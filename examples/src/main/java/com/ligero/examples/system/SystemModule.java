package com.ligero.examples.system;

import com.ligero.Ligero;
import com.ligero.LigeroModule;
import com.ligero.beans.Beans;

public final class SystemModule implements LigeroModule {

    @Override
    public void beans(Beans.Builder builder) {
        builder.bind(SystemController.class, b -> new SystemController());
    }

    @Override
    public void routes(Ligero app, Beans beans) {
        beans.get(SystemController.class).register(app);
    }
}
