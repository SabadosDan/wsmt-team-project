# WSMT Team Project: Distributed Item Management System

Project for the Web Services and Middleware Technologies course. It manages a
catalog of items through a small REST API and pushes change events through
RabbitMQ. The goal is to show, in one runnable demo, the MOM concepts the
course covers: load balancing, failover, persistence, reliable delivery,
horizontal scaling, and message-driven administration.

## Team members

Sfirlea Andrei Bogdan
Sabados Dan

## What it does

A user creates, updates or deletes items through a small HTML page. Each call
hits the Spring Boot backend, which writes to MongoDB and then publishes a
domain event (`items.created`, `items.updated`, `items.deleted`) to a topic
exchange on RabbitMQ. A consumer in the same backend logs every event so we
can watch how the broker routes messages and how a second backend instance
joins as a competing consumer.

## Tech stack

| Component | Version |
|-----------|---------|
| Java | 21 |
| Spring Boot | 3.3.5 |
| Maven | 3.9.x |
| MongoDB | 7.0 |
| RabbitMQ | 3.13.x |
| Client | vanilla HTML + fetch + Pico.css |

## Architecture

```
          +-----------------------+
          |   browser (index.html)|
          +----------+------------+
                     | HTTP (fetch)
                     v
          +----------+------------+        +---------------+
          |  Spring Boot backend  |<------>|   MongoDB     |
          |   /api/items (CRUD)   |  Mongo |   items col.  |
          +----------+------------+        +---------------+
                     | AMQP
                     v
          +----------+------------+
          |       RabbitMQ        |
          |  items.exchange       |
          |  items.queue          |
          +-----------------------+
```

## Getting started

### Prerequisites

- Java 21
- Maven 3.9+
- MongoDB running on `localhost:27017`
- RabbitMQ running on `localhost:5672`

### RabbitMQ setup (one-time)

After installing RabbitMQ, create the admin user and enable the management plugin:

```
rabbitmqctl add_user admin admin
rabbitmqctl set_user_tags admin administrator
rabbitmqctl set_permissions -p / admin ".*" ".*" ".*"
rabbitmq-plugins enable rabbitmq_management
```

The queue and exchange are declared automatically by the backend on startup.

### Run the backend

```
cd backend
mvn spring-boot:run
```

Once started:

- Backend API: `http://localhost:8080/api/items`
- Actuator health: `http://localhost:8080/actuator/health`
- RabbitMQ management UI: `http://localhost:15672` (login `admin` / `admin`)

### Run the client

Open `client/index.html` directly in a browser, or serve it with:

```
python -m http.server 8000
```

from the `client/` folder and visit `http://localhost:8000`.

## Requirements mapping

| Requirement | How we satisfy it |
|-------------|-------------------|
| Web services technology (REST) for client-server communication | `ItemController` exposes `/api/items` with Spring MVC, JSON over HTTP. |
| Client UI with CRUD operations | `client/index.html`, vanilla form + table, `fetch()` calls per operation. |
| MOM load balancing across multiple servers (>=2 nodes) | RabbitMQ cluster config in `docker/rabbitmq/` with HAProxy round-robin (see `docker/haproxy/haproxy.cfg`). |
| MOM failover | Quorum queue replicates the log across nodes (Raft); HAProxy health checks drop dead nodes within 5s. |
| Message persistence | `durable: true` queue, publisher sets `MessageDeliveryMode.PERSISTENT`. |
| Reliable message delivery | Publisher confirms (`publisher-confirm-type: correlated`), mandatory flag, returns callback; consumer-side retry (`spring.rabbitmq.listener.simple.retry.*`). |
| Message queuing for async communication | Producer publishes inside the HTTP handler; consumer runs on a separate listener container thread. |
| MOM fully administered by messages between client and message queue | `rabbitmq_management` plugin enabled; full Management HTTP API on 15672. |
| Horizontal scalability | Multiple backend instances share `items.queue` as competing consumers. |
| MOM security features (CIA) | Confidentiality: TLS config stub in `docker/rabbitmq/rabbitmq.conf`. Integrity: AMQP framing + persistent + confirmed delivery. Availability: quorum queue, HAProxy in front. |
| Works with existing MOM frameworks (ActiveMQ / RabbitMQ / Kafka) | We chose RabbitMQ. AMQP 0-9-1 is a standard wire protocol; the same `RabbitTemplate` interface ports over to ActiveMQ Artemis via JMS or Kafka via Spring Cloud Stream binders. |

## Verifying it works

With MongoDB and RabbitMQ running and the backend started:

```
curl http://localhost:8080/actuator/health
# {"status":"UP"}

curl -s -X POST http://localhost:8080/api/items \
  -H 'content-type: application/json' \
  -d '{"name":"test","description":"hello","quantity":1}'
# returns the saved item with an id
```

Check the backend console — you should see:

```
published event type=created itemId=... routingKey=items.created
[local] received event type=created itemId=... at=...
```

## Demo: horizontal scaling

Start a second backend instance on a different port:

```
cd backend
SERVER_PORT=8081 mvn spring-boot:run
```

Both instances connect to the same `items.queue` as competing consumers.
Create an item and watch both consoles — events round-robin between them.
That is the competing consumers pattern the course calls "horizontal scalability
for MOM workloads".

## Troubleshooting

- **`PRECONDITION_FAILED - inequivalent arg 'x-queue-type'`** — the queue was
  previously declared as `classic`. Delete it from the RabbitMQ management UI
  (Queues → items.queue → Delete) and restart the backend.
- **Backend fails to connect to RabbitMQ** — make sure RabbitMQ is running and
  the `admin` user exists with full permissions on the `/` vhost.
- **Backend fails to connect to MongoDB** — make sure mongod is running on
  `localhost:27017`.
