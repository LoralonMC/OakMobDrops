package dev.oakheart.oakmobdrops.backend;

import dev.oakheart.oakmobdrops.OakMobDrops;
import dev.oakheart.oakmobdrops.model.DropType;

public class DropRouter {
    private final DropBackend executableItems;
    private final DropBackend itemsAdder;
    private final DropBackend nexo;
    private final DropBackend oakpets;
    private final DropBackend vanilla;

    public DropRouter(OakMobDrops plugin) {
        this.executableItems = new ExecutableItemsBackend(plugin);
        this.itemsAdder = new ItemsAdderBackend(plugin);
        this.nexo = new NexoBackend(plugin);
        this.oakpets = new OakPetsBackend(plugin);
        this.vanilla = new VanillaBackend();
    }

    public DropBackend forType(DropType type) {
        return switch (type) {
            case EXECUTABLE_ITEMS -> executableItems;
            case ITEMSADDER -> itemsAdder;
            case NEXO -> nexo;
            case OAKPETS -> oakpets;
            case VANILLA -> vanilla;
        };
    }
}
