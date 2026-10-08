# Bulk Email Service: Diagrams

Mermaid source, so it renders on GitHub. Paste any block into your README.

## 1. System architecture

```mermaid
flowchart LR
    C["Client / Dashboard"] -->|"REST + API key / JWT"| API["Spring Boot API"]
    API -->|"validate, quota check"| R[("Redis<br/>rate limit + quota")]
    API --> DB[("PostgreSQL")]
    SCH["Scheduler<br/>polls due campaigns"] --> DB
    SCH -->|"expand into messages"| Q{{"RabbitMQ<br/>email.send queue"}}
    API -->|"transactional email"| Q
    Q --> W1["Worker 1"]
    Q --> W2["Worker 2"]
    Q --> WN["Worker N"]
    W1 & W2 & WN -->|"token bucket"| R
    W1 & W2 & WN -->|"render + send"| SES["AWS SES / SMTP"]
    W1 & W2 & WN -->|"update status"| DB
    Q -.->|"after retries fail"| DLQ{{"Dead-letter queue"}}
    SES -->|"bounce / complaint"| WH["POST /webhooks/ses"]
    WH --> API
    U["Recipient"] -->|"open pixel, click, unsubscribe"| API
```

## 2. Database ER diagram

```mermaid
erDiagram
    ORGANIZATION ||--o{ API_KEY : has
    ORGANIZATION ||--o{ CONTACT_LIST : owns
    ORGANIZATION ||--o{ TEMPLATE : owns
    ORGANIZATION ||--o{ CAMPAIGN : runs
    ORGANIZATION ||--o{ SUPPRESSION : maintains
    CONTACT_LIST ||--o{ CONTACT : contains
    CONTACT_LIST ||--o{ CAMPAIGN : targets
    TEMPLATE ||--o{ CAMPAIGN : used_by
    CAMPAIGN ||--o{ EMAIL_MESSAGE : produces

    ORGANIZATION {
        uuid id PK
        string email UK
        int daily_quota
        int rate_per_sec
    }
    API_KEY {
        uuid id PK
        uuid org_id FK
        char key_hash UK
        bool active
    }
    CONTACT_LIST {
        uuid id PK
        uuid org_id FK
        string name
    }
    CONTACT {
        uuid id PK
        uuid list_id FK
        citext email
        jsonb attributes
    }
    TEMPLATE {
        uuid id PK
        uuid org_id FK
        string subject
        text html_body
    }
    CAMPAIGN {
        uuid id PK
        uuid org_id FK
        uuid template_id FK
        uuid list_id FK
        string status
        timestamptz scheduled_at
    }
    EMAIL_MESSAGE {
        uuid id PK
        uuid campaign_id FK
        citext to_email
        string status
        string idempotency_key
        timestamptz opened_at
        timestamptz clicked_at
    }
    SUPPRESSION {
        uuid org_id PK
        citext email PK
        string reason
    }
```

## 3. Campaign send flow

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant API as Spring Boot API
    participant DB as PostgreSQL
    participant S as Scheduler
    participant MQ as RabbitMQ
    participant W as Worker
    participant R as Redis
    participant SES as AWS SES

    Client->>API: POST /campaigns (scheduledAt)
    API->>DB: insert campaign (SCHEDULED)
    API-->>Client: 202 Accepted + campaignId

    S->>DB: find due campaigns
    S->>DB: set SENDING, insert email_message rows (skip suppressed)
    S->>MQ: publish message ids in batches

    MQ->>W: deliver message id
    W->>R: take token (per-tenant rate limit)
    W->>DB: load message + contact + template
    W->>W: render {{variables}}, add tracking + unsubscribe link
    W->>SES: send email
    alt success
        W->>DB: status SENT, store provider_message_id
    else transient error
        W->>MQ: requeue with backoff
        Note over W,MQ: after max attempts, goes to DLQ and status FAILED
    end

    SES-->>API: POST /webhooks/ses (bounce or complaint)
    API->>DB: mark BOUNCED, insert suppression row
```

## 4. Status state machines

```mermaid
stateDiagram-v2
    direction LR
    state "Campaign" as Campaign {
        [*] --> SCHEDULED
        SCHEDULED --> SENDING: scheduler picks up
        SENDING --> COMPLETED: all messages final
        SENDING --> FAILED: fatal error
    }
```

```mermaid
stateDiagram-v2
    direction LR
    [*] --> PENDING: row created
    PENDING --> SUPPRESSED: email in suppression list
    PENDING --> QUEUED: published to RabbitMQ
    QUEUED --> SENT: SES accepted
    QUEUED --> FAILED: retries exhausted
    SENT --> BOUNCED: bounce webhook
```