# hk-bill-split

**一齊夾** — HKD bill splitting for friends, with a Cantonese / Traditional Chinese, mobile-first interface. No registration: a group is created instantly and its private link is the only key, like Bill Bear.

Java 21 + Spring Boot 4.0, React 19 + TypeScript + Vite, PostgreSQL 17. One production Java container serves both the React website and the API.

## How it works

1. Open the site, enter a group name and who is in it → the group exists immediately.
2. Share the private link `/g/<random-code>` in WhatsApp. Anyone with the link can view and add to the group; nobody needs an account.
3. Each device picks "who am I" once (stored locally) so expenses default to you and balances are shown from your point of view.
4. Record expenses with one payer, a category, and an equal / exact / percentage split. The amount box accepts sums such as `120+80`.
5. The 結餘 tab shows every balance and the minimal set of transfers to settle. Tap 已還錢 after paying by FPS / PayMe / bank / cash — it takes effect immediately, no confirmation step.
6. The 動態 tab is the change history. Phones poll every few seconds so the group stays in sync.

This app records payments made elsewhere; it does not transfer or verify money. **The link is the credential** — anyone holding it can read and edit the group, so only send it to the people in the group. Out of scope for now: offline mode, multiple currencies, attachments, recurring expenses, removing members, and deleting groups.

## Run locally

Requirements: Java **21**, Maven **3.6.3+**, Node **22 LTS**, npm, and Docker with Compose.

```sh
docker compose up -d --wait
npm --prefix frontend ci
```

Start the API in one terminal and the frontend in another:

```sh
mvn -f backend/pom.xml spring-boot:run -Dspring-boot.run.profiles=local
npm --prefix frontend run dev
```

Open `http://localhost:5173`, create a group, then open the share link in a private window to act as another member.

If you have a database from the earlier account-based version, drop its `billsplit` schema first (`docker compose down -v` resets local data).

Single-process build:

```sh
npm --prefix frontend run build
mvn -f backend/pom.xml -DskipTests package
java -jar backend/target/bill-split-0.1.0.jar --spring.profiles.active=local
```

## Checks

```sh
npm --prefix frontend run lint
npm --prefix frontend run typecheck
npm --prefix frontend test
npm --prefix frontend run build
mvn -f backend/pom.xml spotless:check verify
```

Backend tests use Testcontainers to start a real PostgreSQL and run Flyway; they cover rounding, split totals, idempotency, stale edits, concurrent writes, member uniqueness, secret-code lookup, and the HTTP contract. `mvn -f backend/pom.xml spotless:apply` formats Java.

## Production: Render + PostgreSQL

Connect the repository to Render and create a **Blueprint** from `render.yaml`. The service needs three secrets:

| Variable | Value |
|---|---|
| `DATABASE_URL` | `jdbc:postgresql://HOST:5432/DBNAME?sslmode=require` |
| `DATABASE_USERNAME` | Database user |
| `DATABASE_PASSWORD` | Database password |

Any managed PostgreSQL works: Render Postgres, Supabase (use the **session pooler** on port 5432, not the transaction pooler), Neon, etc. Flyway creates the `billsplit` schema and tables on first start. The startup role needs schema-creation privileges for migrations; a lower-privilege runtime role can be configured through `SPRING_FLYWAY_URL` / `SPRING_FLYWAY_USER` / `SPRING_FLYWAY_PASSWORD`.

The image runs as an unprivileged user and exposes a database-aware `/health` endpoint. Other Docker hosts: `cp .env.example .env`, fill it in, `docker build -t hk-bill-split .`, `docker run --rm --env-file .env -p 8080:8080 hk-bill-split`, behind an HTTPS reverse proxy.

## Money, security, and privacy

Amounts are decimal strings at the API boundary and integer cents in the ledger (`long` / `BIGINT`), capped at HK$1,000,000 per entry. Equal and percentage splits use largest-remainder allocation with stable UUID ordering. Exact shares must sum to the total; percentages must sum to 100%.

```text
balance = expenses paid − allocated shares + transfers sent − transfers received
positive = money to receive; negative = money to repay
```

- Group codes are 160-bit random URL-safe strings; the database stores only their SHA-256 hash. Unknown or malformed codes return 404.
- Expense and transfer creation require a UUID `Idempotency-Key`; the same key with a changed payload returns 409. Edits require the current expense `version`.
- Writes serialize on the group row; activity records share the ledger transaction.
- The optional `X-Member` header only attributes activity ("who did this"); it grants nothing.
- Anonymous writes are limited to 120/minute per client address on one instance.
- Member names are unique per group (case-insensitive) and members cannot be removed, so history stays consistent; rename instead.

## API

All endpoints are public; the group code in the path is the credential. Errors have a stable `code`.

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/v1/groups` | Create `{ name, emoji?, members: [names] }` → returns the one-time `code` |
| GET | `/api/v1/g/{code}` | Snapshot: group, members, expenses, transfers, balances, suggestions |
| PATCH | `/api/v1/g/{code}` | Rename group / change emoji |
| POST | `/api/v1/g/{code}/members` | Add member `{ name }` |
| PATCH | `/api/v1/g/{code}/members/{id}` | Rename member |
| POST / PATCH / DELETE | `/api/v1/g/{code}/expenses[/{id}]` | Create / edit / delete expense |
| POST / DELETE | `/api/v1/g/{code}/transfers[/{id}]` | Record / delete a repayment |
| GET | `/api/v1/g/{code}/activity?offset=0` | History, 50 per page with `hasMore` |
| GET | `/health` | Database-aware health check |

Expense body (amounts are strings, IDs are member IDs):

```json
{
  "payerMemberId": "…", "description": "週五晚飯", "category": "FOOD", "amount": "600.00",
  "splitMethod": "EQUAL", "incurredOn": "2026-09-15",
  "participants": [{"memberId": "…"}, {"memberId": "…"}]
}
```

For `EXACT`, each participant adds a decimal `value` such as `"300.00"`; for `PERCENT`, a percentage such as `"50"`. Categories: `FOOD, TRANSPORT, STAY, SHOPPING, FUN, OTHER`. Transfer methods: `FPS, PAYME, BANK, CASH`.

## Backups

Keep regular `pg_dump --format=custom --schema=billsplit` exports outside the app host and rehearse `pg_restore` into an empty database. The dump contains your friends' financial data; store it privately.
