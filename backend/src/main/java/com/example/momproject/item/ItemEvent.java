package com.example.momproject.item;

import java.time.Instant;

public record ItemEvent(String type, String itemId, Instant occurredAt, Item snapshot) {

    public static ItemEvent created(Item item) {
        return new ItemEvent("created", item.getId(), Instant.now(), item);
    }

    public static ItemEvent updated(Item item) {
        return new ItemEvent("updated", item.getId(), Instant.now(), item);
    }

    public static ItemEvent deleted(String id) {
        return new ItemEvent("deleted", id, Instant.now(), null);
    }
}
