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

### Open the client

Once the backend is running, open `http://localhost:8080` in a browser.
The UI is served directly by Spring Boot from `src/main/resources/static/index.html`.

## Running the clustered setup with Docker

For the full demo (3-node RabbitMQ cluster, HAProxy load balancing, failover)
you need Docker Desktop with Compose v2.

```
cd docker
docker compose up -d
```

The first boot pulls images and builds the backend JAR — give it a few minutes.
Verify everything is healthy:

```
docker compose ps
# all services should show (healthy)

docker exec rabbit1 rabbitmqctl cluster_status
# Running Nodes: rabbit@rabbit1, rabbit@rabbit2, rabbit@rabbit3

docker exec rabbit1 rabbitmqctl list_queues name type leader members
# items.queue  quorum  rabbit@rabbitN  [rabbit@rabbit1 rabbit@rabbit2 rabbit@rabbit3]

curl http://localhost:8080/actuator/health
# {"status":"UP"}
```

Once up, open `http://localhost:8080` for the UI and `http://localhost:15672`
for the RabbitMQ management console (login `admin` / `admin`).

### Demo: failover

Kill one node and keep posting — the quorum queue and HAProxy keep the service alive.

```
docker compose stop rabbit2

# in a second terminal, keep creating items:
curl -s -X POST http://localhost:8080/api/items \
  -H 'content-type: application/json' \
  -d '{"name":"during-failover","description":"x","quantity":1}'

docker exec rabbit1 rabbitmqctl cluster_status
# rabbit@rabbit2 no longer listed under Running Nodes

docker exec rabbit1 rabbitmqctl list_queues name type leader members
# leader has moved to rabbit1 or rabbit3

docker compose start rabbit2
# wait ~15s — rabbit2 rejoins the cluster
```

## Requirements mapping

| Requirement | How we satisfy it |
|-------------|-------------------|
| Web services technology (REST) for client-server communication | `ItemController` exposes `/api/items` with Spring MVC, JSON over HTTP. |
| Client UI with CRUD operations | `src/main/resources/static/index.html`, vanilla form + table, `fetch()` calls per operation. |
| MOM load balancing across multiple servers (>=2 nodes) | 3-node RabbitMQ cluster (`rabbit1/2/3`) declared in `docker/rabbitmq/rabbitmq.conf`; HAProxy AMQP frontend in `docker/haproxy/haproxy.cfg` balances across all three with `balance roundrobin`. |
| MOM failover | Quorum queue replicates its log across all 3 nodes (Raft consensus); HAProxy `option tcp-check inter 5s` drops a dead node within ~5 s; `cluster_partition_handling = pause_minority` prevents split-brain. See "Demo: failover" above. |
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

- **`PRECONDITION_FAILED - inequivalent arg 'x-queue-type'`** (Docker path) —
  a previous run declared `items.queue` as `classic` and the volume still holds
  that state. Wipe volumes and restart: `docker compose down -v && docker compose up -d`.
- **HAProxy kills idle AMQP connections** — the default HAProxy timeouts are too
  short for long-lived AMQP connections. `docker/haproxy/haproxy.cfg` already
  sets `timeout client/server 3h` and `option clitcpka/srvtcpka`; `application.yml`
  sets `requested-heartbeat: 30s`. Leave both as-is.
- **Backend fails to connect to RabbitMQ** (local path) — make sure RabbitMQ is
  running and the `admin` user exists with full permissions on the `/` vhost.
- **Backend fails to connect to MongoDB** (local path) — make sure mongod is
  running on `localhost:27017`.
