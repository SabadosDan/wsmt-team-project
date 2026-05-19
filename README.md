# WSMT Team Project: Distributed Item Management System

Project for the Web Services and Middleware Technologies course. It manages a
catalog of items through a small REST API and pushes change events through a
RabbitMQ cluster sitting behind HAProxy. The goal is to show, in one runnable
demo, the MOM concepts the course covers: load balancing, failover,
persistence, reliable delivery, horizontal scaling, and message-driven
administration.

## Team members

Sfirlea Andrei Bogdan
Sabados Dan

## What it does

A user creates, updates or deletes items through a small HTML page. Each call
hits the Spring Boot backend, which writes to MongoDB and then publishes a
domain event (`items.created`, `items.updated`, `items.deleted`) to a topic
exchange on RabbitMQ. A consumer in the same backend logs every event so we
can watch how the cluster routes messages, what happens when a node dies, and
how a second backend instance joins as a competing consumer.

We picked a generic `Item` entity (name, description, quantity, createdAt)
because we haven't settled on the real domain yet. Once we do, we rename the
package and move on.

## Tech stack

| Component | Version |
|-----------|---------|
| Java | 21 |
| Spring Boot | 3.3.5 |
| Maven | 3.9.x |
| MongoDB | 7.0.14 |
| RabbitMQ | 3.13.7 (3-node cluster, quorum queue) |
| HAProxy | 2.9.10 |
| Docker Compose | v2 |
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
                |       HAProxy         |
                |  5672 (AMQP, tcp RR)  |
                | 15672 (mgmt, http+ck) |
                +--+----------+--------++
                   |          |        |
                   v          v        v
              +--------+ +--------+ +--------+
              |rabbit1 | |rabbit2 | |rabbit3 |
              |        | |        | |        |
              +---+----+ +---+----+ +---+----+
                  \________ cluster ________/
                       (items.queue: quorum)
```

The backend never talks to a specific rabbit node directly. It always goes
through HAProxy, which is how we get load balancing and failover without the
backend caring which node is alive.

## Requirements mapping

| Requirement | How we satisfy it |
|-------------|-------------------|
| Web services technology (REST) for client-server communication | `ItemController` exposes `/api/items` with Spring MVC, JSON over HTTP. |
| Client UI with CRUD operations | `client/index.html`, vanilla form + table, `fetch()` calls per operation. |
| MOM load balancing across multiple servers (>=2 nodes) | 3-node RabbitMQ cluster (`rabbit1/2/3`), HAProxy AMQP frontend on 5672 with `balance roundrobin`. |
| MOM failover | Quorum queue replicates the log across the 3 nodes (Raft); HAProxy `option tcp-check` drops dead nodes within ~5s; `cluster_partition_handling = pause_minority`. |
| Message persistence | Quorum queue (writes on disk on a majority), `durable: true`, publisher sets `MessageDeliveryMode.PERSISTENT`. |
| Reliable message delivery | Publisher confirms (`publisher-confirm-type: correlated`), mandatory flag, returns callback; consumer-side retry (`spring.rabbitmq.listener.simple.retry.*`). |
| Message queuing for async communication | Producer publishes inside the HTTP handler; consumer runs on a separate listener container thread. |
| MOM fully administered by messages between client and message queue | `rabbitmq_management` plugin enabled; full Management HTTP API on 15672; `rabbitmqadmin` CLI inside the container. |
| Horizontal scalability | Multiple backend instances share `items.queue` as competing consumers; commands documented above. |
| MOM security features (CIA) | Confidentiality: TLS config stub in `rabbitmq.conf`, ports bound to 127.0.0.1 only. Integrity: AMQP framing + persistent + confirmed delivery. Availability: 3-node quorum, HAProxy in front, `pause_minority` on partitions. |
| Works with existing MOM frameworks (ActiveMQ / RabbitMQ / Kafka) | We chose RabbitMQ. AMQP 0-9-1 is a standard wire protocol; the same `RabbitTemplate` interface ports over to ActiveMQ Artemis via JMS or Kafka via Spring Cloud Stream binders. |

## Getting started

You need Docker Desktop (with Compose v2) and that is it. Everything else runs
in containers.

```
cd docker
docker compose up -d
docker compose ps
```

The first boot pulls images and builds the backend JAR, so give it a couple of
minutes. Once everything is healthy:

- Backend API: `http://localhost:8080/api/items`
- Actuator health: `http://localhost:8080/actuator/health`
- RabbitMQ management UI: `http://localhost:15672` (login `admin` / `admin`)
- HAProxy stats: `http://localhost:8404/stats`
- Client UI: open `client/index.html` directly in a browser, or serve it with
  `python -m http.server 8000` from the `client/` folder and visit
  `http://localhost:8000`.

The credentials `admin` / `admin` are baked into `docker/rabbitmq/definitions.json`
and the `rabbitmq.conf` fallback. They are intentionally trivial because nothing
is exposed beyond `127.0.0.1`. Change them for anything other than a course
demo.

## Verifying it actually works

After `docker compose up -d`, run these in order. All should pass.

```
docker compose ps
# every service should report (healthy)

docker exec rabbit1 rabbitmqctl cluster_status
# should list rabbit@rabbit1, rabbit@rabbit2, rabbit@rabbit3 under
# "Running Nodes" with no partitions reported

docker exec rabbit1 rabbitmqctl list_queues name type leader members
# items.queue  quorum  rabbit@rabbitN  [rabbit@rabbit1, rabbit@rabbit2, rabbit@rabbit3]

curl http://localhost:8080/actuator/health
# {"status":"UP", ...}

curl -s -X POST http://localhost:8080/api/items \
  -H 'content-type: application/json' \
  -d '{"name":"test","description":"hello","quantity":1}'
# returns the saved item with an id

docker compose logs backend | grep "received event"
# you should see the listener log the event the publisher just sent
```

If `items.queue` shows `classic` instead of `quorum`, the volumes were carrying
over a previous declaration. Reset with `docker compose down -v` and start
again.

## Demo: failover

The point of a 3-node quorum is that we can lose one node and keep going. Kill
rabbit2 and watch the cluster react.

```
docker compose stop rabbit2

# from a second shell, keep posting items (they should still succeed):
while true; do
  curl -s -X POST http://localhost:8080/api/items \
    -H 'content-type: application/json' \
    -d '{"name":"during-failover","description":"x","quantity":1}' \
    -o /dev/null -w "%{http_code}\n"
  sleep 1
done

docker exec rabbit1 rabbitmqctl cluster_status
# rabbit@rabbit2 now appears under "Disk Nodes" but not "Running Nodes"

docker exec rabbit1 rabbitmqctl list_queues name type leader members
# if rabbit2 was the leader, a new leader has been elected on rabbit1 or rabbit3

docker compose start rabbit2
# wait ~15s
docker exec rabbit1 rabbitmqctl cluster_status
# all three back under "Running Nodes"
```

HAProxy notices the dead node within `inter 5s` and stops sending traffic to
it. The backend doesn't reconnect to a different host, it stays connected to
HAProxy and HAProxy transparently routes it to a healthy node.

## Demo: horizontal scaling

Compose's `--scale` flag won't work straight out of the box because the
backend service publishes port `8080:8080` and a second container can't bind
the same host port. Two options:

Option A, drop the host port mapping on the extra instance:

```
docker compose run -d --service-ports=false --name backend2 backend
docker logs -f backend2
```

Now create an item; check `docker logs backend` and `docker logs backend2`.
The `items.queue` is a single queue with two consumers, events round-robin
between them. That's the competing consumers pattern, which is what the
course calls "horizontal scalability for MOM workloads".

Option B, duplicate the service in a `docker-compose.override.yml`. Slightly
more boilerplate but plays nice with `compose up`.

## Roadmap

Done:
- 3-node RabbitMQ cluster with quorum queue (persistent + replicated + auto failover)
- HAProxy in front for AMQP and management UI
- MongoDB-backed CRUD with REST
- Producer publishes events on every write, consumer logs them
- Vanilla HTML client
- Docker Compose for everything
- Failover demo, horizontal scaling demo

Planned (if there is time before the presentation):
- TLS on AMQP (config stub is already in `rabbitmq.conf`)
- A real domain model instead of `Item`
- A nicer frontend (probably still vanilla, just less ugly)
- A GitHub Actions workflow that builds the JAR and runs unit tests

## Troubleshooting

A few things have bitten us; documenting them so a fresh clone goes smoothly.

- **`PRECONDITION_FAILED - inequivalent arg 'x-queue-type'`** when starting.
  The queue was previously declared as `classic` in a prior run and the volume
  still has the old definition. Run `docker compose down -v` to wipe the
  RabbitMQ volumes and restart.
- **Backend logs `ShutdownSignalException: connection error` every minute or
  so.** HAProxy default `timeout client/server` was killing idle AMQP
  connections faster than the AMQP heartbeat could fire. We bumped the
  HAProxy timeouts and lowered Spring's `requested-heartbeat`, see
  `haproxy.cfg` and `application.yml`.
- **rabbit2 and rabbit3 fail to start with "Failed to write cookie file".**
  This is an Erlang/OTP 26 file-perms thing. We mount the cookie via Compose
  `configs:` with `mode: 0400` and uid/gid `999` (the rabbitmq user inside
  the image). Don't switch to `RABBITMQ_ERLANG_COOKIE`, it's deprecated and
  noisier.
