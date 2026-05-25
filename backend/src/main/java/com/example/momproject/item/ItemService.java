package com.example.momproject.item;

import com.example.momproject.messaging.ItemEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class ItemService {

    private final ItemRepository repository;
    private final ItemEventPublisher publisher;

    public ItemService(ItemRepository repository, ItemEventPublisher publisher) {
        this.repository = repository;
        this.publisher = publisher;
    }

    public List<Item> findAll() {
        return repository.findAll();
    }

    public Optional<Item> findById(String id) {
        return repository.findById(id);
    }

    public Item create(Item item) {
        item.setCreatedAt(Instant.now());
        Item saved = repository.save(item);
        publisher.publish(ItemEvent.created(saved));
        return saved;
    }

    public Optional<Item> update(String id, Item incoming) {
        return repository.findById(id).map(existing -> {
            existing.setName(incoming.getName());
            existing.setDescription(incoming.getDescription());
            existing.setQuantity(incoming.getQuantity());
            Item saved = repository.save(existing);
            publisher.publish(ItemEvent.updated(saved));
            return saved;
        });
    }

    public boolean delete(String id) {
        if (!repository.existsById(id)) {
            return false;
        }
        repository.deleteById(id);
        publisher.publish(ItemEvent.deleted(id));
        return true;
    }
}
