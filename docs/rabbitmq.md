# RabbitMQ dead-letter setup

Run this once when configuring a new local RabbitMQ broker.
Paste the command as one line in PowerShell:

```powershell
docker --% exec notifyflow-rabbitmq rabbitmqctl set_policy -p notifyflow --apply-to queues notifyflow-delivery-dlx "^notifyflow\.deliveries\.jobs$" "{\"dead-letter-exchange\":\"notifyflow.deliveries.dead\",\"dead-letter-routing-key\":\"delivery.dead\"}"
```

The policy forwards rejected jobs from `notifyflow.deliveries.jobs`
to `notifyflow.deliveries.dead.jobs`.

Malformed delivery IDs are rejected without requeueing.

HTTP retries use PostgreSQL scheduling. Exhausted deliveries remain
`FAILED` in PostgreSQL and do not automatically enter the dead-letter queue.