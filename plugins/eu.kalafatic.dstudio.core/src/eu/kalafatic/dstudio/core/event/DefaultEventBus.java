package eu.kalafatic.dstudio.core.event;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class DefaultEventBus implements EventBus {

    private final Map<Class<?>, List<Subscriber<?>>> subscribers = new ConcurrentHashMap<>();

    @Override
    public <T> void publish(T event) {
        if (event == null) {
            return;
        }
        subscribers.forEach((type, list) -> {
            if (type.isInstance(event)) {
                for (Subscriber<?> subscriber : list) {
                    deliver(subscriber, event);
                }
            }
        });
    }

    @SuppressWarnings("unchecked")
    private <T> void deliver(Subscriber<?> subscriber, T event) {
        try {
            ((Subscriber<T>) subscriber).listener().accept(event);
        } catch (RuntimeException e) {
            System.err.println("EVO-Studio event listener failed: " + e.getMessage());
        }
    }

    @Override
    public <T> AutoCloseable subscribe(Class<T> eventType, Consumer<T> listener) {
        Subscriber<T> subscriber = new Subscriber<>(eventType, listener);
        subscribers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(subscriber);
        return () -> {
            List<Subscriber<?>> list = subscribers.get(eventType);
            if (list != null) {
                list.remove(subscriber);
            }
        };
    }

    private record Subscriber<T>(Class<T> eventType, Consumer<T> listener) {
    }
}
