package com.limelight.console;

import org.junit.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GatewayProfileRefreshControllerTest {
    private static final String PIN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test public void latestSuccessfulRequestIsDelivered() {
        IntegrationProfileCatalog catalog = new IntegrationProfileCatalog(
                Collections.emptyList(), null);
        AtomicInteger loaded = new AtomicInteger();
        GatewayProfileRefreshController controller = controller(connection -> catalog);

        controller.refresh(connection(), callback(loaded, new AtomicInteger()));

        assertEquals(1, loaded.get());
    }

    @Test public void cancelledRequestCannotUpdateUi() {
        AtomicInteger loaded = new AtomicInteger();
        final Runnable[] queued = new Runnable[1];
        GatewayProfileRefreshController controller = new GatewayProfileRefreshController(
                connection -> new IntegrationProfileCatalog(Collections.emptyList(), null),
                Runnable::run, action -> queued[0] = action, () -> { });

        controller.refresh(connection(), callback(loaded, new AtomicInteger()));
        controller.cancel();
        queued[0].run();

        assertEquals(0, loaded.get());
    }

    @Test public void failureIsReducedToSecretFreeUnavailableState() {
        AtomicInteger unavailable = new AtomicInteger();
        GatewayProfileRefreshController controller = controller(connection -> {
            throw new IOException("token must never reach UI");
        });

        controller.refresh(connection(), callback(new AtomicInteger(), unavailable));

        assertEquals(1, unavailable.get());
    }

    @Test public void destroyCancelsDeliveryAndShutsDownWorker() {
        AtomicBoolean shutdown = new AtomicBoolean();
        GatewayProfileRefreshController controller = new GatewayProfileRefreshController(
                connection -> new IntegrationProfileCatalog(Collections.emptyList(), null),
                action -> { }, Runnable::run, () -> shutdown.set(true));

        controller.destroy();

        assertTrue(shutdown.get());
        assertEquals(-1, controller.refresh(connection(),
                callback(new AtomicInteger(), new AtomicInteger())));
    }

    private static GatewayProfileRefreshController controller(
            GatewayProfileRefreshController.Loader loader) {
        return new GatewayProfileRefreshController(
                loader, Runnable::run, Runnable::run, () -> { });
    }

    private static GatewayProfileRefreshController.Callback callback(
            AtomicInteger loaded, AtomicInteger unavailable) {
        return new GatewayProfileRefreshController.Callback() {
            @Override public void onLoaded(IntegrationProfileCatalog catalog) {
                loaded.incrementAndGet();
            }

            @Override public void onUnavailable() {
                unavailable.incrementAndGet();
            }
        };
    }

    private static GatewayConnection connection() {
        return new GatewayConnection("https://host", "secret", PIN, "default");
    }
}
