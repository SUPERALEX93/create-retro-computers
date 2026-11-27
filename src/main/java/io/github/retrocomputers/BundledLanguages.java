package io.github.retrocomputers;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

@EventBusSubscriber(modid = RetroComputers.ID)
public final class BundledLanguages {
    @SubscribeEvent
    public static void start(ServerStartedEvent event) {
        NativeToolchains.configure(ComputerStorage.base().getParent().resolve("toolchains"));
        NativeToolchains.start();
    }
}
