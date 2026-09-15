# hk-bill-split

**一齊夾** — private HKD bill splitting for friends, with a Cantonese / Traditional Chinese interface.

Java 21 + Spring Boot 4.0, React 19 + TypeScript + Vite, PostgreSQL 17, and Supabase Auth. One production Java container serves both the React website and the API.

## Included

- Private groups and seven-day, single-use invitation links.
- HKD expenses with one payer and equal, exact-amount, or percentage splits.
- Expense editing with version checks, voiding, and audit history.
- Balances and deterministic repayment suggestions.
- FPS / PayMe / bank / cash repayment records; **only recipient-confirmed payments change balances**.
- Pending-payment rejection and cancellation.
- Google sign-in through Supabase; a separate local demo mode.
- PostgreSQL migrations, integration tests, CI, Docker, and Render configuration.

This app records payments made elsewhere; it does not transfer or verify money. Everyone who joins a group can see that group's history. Invite links grant group access, so send them privately. Initial scope excludes multiple currencies/payers, attachments, recurring expenses, user removal, and group archival controls.

## Run locally

Requirements: Java **21**, Maven **3.6.3+** (the Docker build uses 3.9.9), Node **22 LTS**, npm, and Docker with Compose. A JDK, rather than a JRE alone, is needed to compile. There are no Git hooks to install.

From the repository root:

```sh
docker compose up -d --wait
npm --prefix frontend ci
```

Start the API in one terminal:

```sh
mvn -f backend/pom.xml spring-boot:run -Dspring-boot.run.profiles=demo
```

Start the frontend in another:

```sh
npm --prefix frontend run dev
```

Open `http://localhost:5173`. Choose one of the three demo identities. Create a group, generate invitations, and use another browser profile/private window to join as another demo identity. Add an expense, record an external repayment as its sender, and confirm it as the recipient.

Local data persists in the Compose volume. `docker compose stop` preserves it. **Demo identities are unauthenticated and must only be used locally.** The demo API binds to loopback; the production container forces the `prod` profile and rejects demo headers.

For a single-process local build:

```sh
npm --prefix frontend run build
mvn -f backend/pom.xml -DskipTests package
java -jar backend/target/bill-split-0.1.0.jar --spring.profiles.active=demo
```

Open `http://localhost:8080`. Maven packages the existing frontend build; always rebuild the frontend first. Local Maven/Java execution does not automatically load `.env`.

## Checks

```sh
npm --prefix frontend run lint
npm --prefix frontend run typecheck
npm --prefix frontend test
npm --prefix frontend run build
mvn -f backend/pom.xml spotless:check verify
```

Docker must be running for Testcontainers. Backend tests start a disposable **real PostgreSQL** database and run Flyway. Coverage includes rounding, exact/percentage totals, permissions, idempotency, stale edits, settlement transitions, concurrent writes, invitation expiry, and signed JWT validation. Frontend tests check financial previews; TypeScript is strict and ESLint checks hooks.

Use `mvn -f backend/pom.xml spotless:apply` to format Java. CI builds the frontend before packaging Java and publishes the combined JAR as an artifact. Browser testing and live Google OAuth still need a configured deployment.

## Production: Render + Supabase

### 1. Supabase

Create a Supabase project in a nearby region. Record the **Project URL**, **publishable key**, and database connection details. Never use a service-role/secret key in the publishable-key setting.

Under Authentication, enable **Google**. In Google Cloud, configure the OAuth consent screen and a Web application OAuth client. Add the callback URL displayed by Supabase (normally `https://YOUR_PROJECT.supabase.co/auth/v1/callback`) as an authorized redirect URI; put the Google client ID and secret into Supabase. If the Google OAuth app is in testing mode, add your friends as test users.

Use an **asymmetric JWT signing key (ES256 or RS256)** in Supabase Auth. This backend validates the public JWKS; the legacy shared HS256 secret is not supported.

Keep the `billsplit` application schema out of Supabase's exposed Data API schemas. The browser only uses Supabase for authentication and accesses bills through Java. Flyway creates the schema and tables at startup.

### 2. Database connection

Use the Supabase **session pooler** connection shown by Connect, normally port **5432**, when your host needs IPv4. Do not use the transaction pooler on port 6543 for application startup/Flyway. Convert the connection URI to JDBC form and supply the password separately:

| Variable | Value |
|---|---|
| `DATABASE_URL` | `jdbc:postgresql://SESSION_POOLER_HOST:5432/postgres?sslmode=require` |
| `DATABASE_USERNAME` | The username shown for that connection, usually `postgres.PROJECT_REF` |
| `DATABASE_PASSWORD` | The database password |
| `SUPABASE_URL` | `https://PROJECT_REF.supabase.co` |
| `SUPABASE_PUBLISHABLE_KEY` | Supabase's **publishable** key |
| `PORT` | Supplied by Render, defaults to 8080 elsewhere |

The startup connection needs schema/table creation privileges for migrations. For a dedicated application login with fewer privileges after initial setup, grant usage on `billsplit`, SELECT/INSERT/UPDATE/DELETE on its tables, and configure migration credentials separately using `SPRING_FLYWAY_URL`, `SPRING_FLYWAY_USER`, and `SPRING_FLYWAY_PASSWORD`. Keep schema creation/migration privileges out of the runtime role. Future migrations must grant any new tables to that role.

### 3. Render

Connect the GitHub repository to Render and create a **Blueprint** using `render.yaml`. Fill the five required variables as Render secrets/settings. The blueprint defaults to the free plan; choose Starter if you want an always-running server. Render builds the Dockerfile and gives the service an HTTPS `onrender.com` URL. No custom domain is required.

Set Supabase Authentication **Site URL** to that URL, and allow its exact root URL with a trailing slash as a redirect, for example `https://YOUR_SERVICE.onrender.com/`. The frontend uses that root URL for Google login. Avoid wildcard production redirect entries.

The image runs as an unprivileged user, defaults to production authentication, and exposes a database-aware `/health` endpoint. Java validates JWT signature, issuer, audience, expiry, and role; it ignores `X-Demo-User` in production.

Do not set the `demo` profile on a public server. For a friends-only pilot, configure Google OAuth test users or an appropriate identity-provider access restriction; group invitations protect group data but do not prevent other Google users from creating independent accounts when Google sign-in is open.

Official setup references:
- https://supabase.com/docs/guides/auth/social-login/auth-google
- https://supabase.com/docs/guides/auth/signing-keys
- https://supabase.com/docs/guides/database/connecting-to-postgres
- https://render.com/docs/blueprint-spec

### Other Docker hosts

```sh
cp .env.example .env
# Edit .env with your production settings, then:
docker build -t hk-bill-split .
docker run --rm --env-file .env -p 8080:8080 hk-bill-split
```

Use an HTTPS reverse proxy in front of the container. The provided CSP allows the standard `*.supabase.co` domain; update it deliberately if using a custom Supabase domain.

## Money, transactions, and privacy

Amounts are decimal strings at the API boundary and integer cents in the ledger (`long` / `BIGINT`), capped at HK$1,000,000 per entry. Equal and percentage splits use largest-remainder allocation with stable UUID ordering. Exact shares must sum to the total; percentages must sum to 100%.

```text
balance = expenses paid − allocated shares
          + confirmed repayments sent − confirmed repayments received
positive = money to receive; negative = money to repay
```

Expense/settlement creation requires a UUID `Idempotency-Key`. Keep the same key and payload after a lost response; a changed payload using the same key returns 409. Edits/voids require the current expense `version`. Writes serialize on the group row; invitation redemption also locks the invitation. Audit writes share the same transaction as the ledger change. Balance and expense reads use a consistent database snapshot.

Only the expense creator or group owner can edit/void it. Only the recipient can confirm/reject a repayment; only the sender can cancel a pending one. Confirmation is repeatable without double-counting. Overpayments are allowed and can reverse balances. Voiding an expense does not erase confirmed repayments; correcting those requires an explicit compensating repayment.

Invitation tokens are random, stored only as hashes, and removed from the browser URL before login. Audit records contain old/new ledger values, not invitation secrets. The in-memory write limiter allows 120 writes/minute/user on one instance; use a shared limiter if scaling horizontally.

## API

All protected endpoints begin with `/api/v1` and require `Authorization: Bearer <Supabase access token>`.

| Method | Path | Purpose |
|---|---|---|
| GET / PUT | `/me` | Read/create/update display name |
| GET / POST | `/groups` | List/create groups |
| GET | `/groups/{id}` / `/groups/{id}/members` | Group and members |
| GET / POST | `/groups/{id}/invitations` | Owner: list/create invites |
| POST | `/groups/{id}/invitations/{inviteId}/revoke` | Owner: revoke |
| POST | `/invitations/accept` | Accept `{ "token": "..." }` |
| GET / POST | `/groups/{id}/expenses` | List/create expenses |
| GET / PATCH | `/expenses/{id}` | Get/edit expense |
| POST | `/expenses/{id}/void` | Void with `{ "version": 0 }` |
| GET | `/groups/{id}/balances` | Member balances and suggestions |
| GET / POST | `/groups/{id}/settlements` | List/record repayments |
| POST | `/settlements/{id}/confirm` | Recipient confirms |
| POST | `/settlements/{id}/reject` | Recipient rejects |
| POST | `/settlements/{id}/cancel` | Sender cancels |
| GET | `/groups/{id}/audit` | Audit history |

List endpoints for expenses, settlements, and audit accept `offset=0`, return up to 50 items, and include `hasMore`. Errors have a stable `code`. Nonmembers receive 404 for group objects.

Expense creation example (amounts are strings, IDs are member IDs):

```json
{
  "description": "週五晚飯",
  "amount": "600.00",
  "payerMemberId": "00000000-0000-0000-0000-000000000001",
  "splitMethod": "EQUAL",
  "incurredOn": "2026-09-15",
  "participants": [
    {"memberId": "00000000-0000-0000-0000-000000000001"},
    {"memberId": "00000000-0000-0000-0000-000000000002"}
  ]
}
```

For `EXACT`, each participant adds a decimal `value` such as `"300.00"`; for `PERCENT`, use a percentage such as `"50"`.

## Backups and recovery

Supabase Free has inactivity and backup limitations. Keep regular exports **outside** the app host; do not rely on Render's filesystem. A schema dump contains private financial data.

Using a PostgreSQL 17+ client, with standard `PGHOST`, `PGPORT`, `PGUSER`, `PGDATABASE`, `PGSSLMODE`, and `PGPASSWORD` configured privately:

```sh
pg_dump --format=custom --schema=billsplit --file=billsplit.dump
# Against a separate EMPTY recovery database:
pg_restore --no-owner --no-acl --dbname=RECOVERY_DATABASE billsplit.dump
```

Do not restore over a live database without a recovery plan. A `billsplit` schema export excludes Supabase Auth users: it supports recovery within the same auth project, but migrating projects also needs an auth-user/UUID migration strategy. Periodically rehearse restore and verify balances. Protect backups with restricted access/encryption and retention rules.

Before inviting friends in production, verify Google login, invitation redemption, expense creation, sender repayment, recipient confirmation, and backup recovery using the actual hosted configuration.