# Microservices Platform (product-services)

> Conventions and gotchas for the Spring Cloud Alibaba service skeleton introduced in the
> `09-14-spring-cloud-alibaba-migration` task (Phase 1). Authoritative task-level decisions live in
> `.trellis/tasks/09-14-spring-cloud-alibaba-migration/adr/` and `product-services/README.md`;
> this file captures only what future code in this repo must follow.

---

## Version Baseline (locked by ADR-0001)

| Component | Version |
|-----------|---------|
| JDK | 17 |
| Spring Boot | 3.5.16 (property `spring-boot.version` in root pom) |
| Spring Cloud | 2025.0.3 |
| Spring Cloud Alibaba | 2025.0.0.0 |
| Nacos server | 3.0.3 |
| Sentinel | 1.8.9 (managed by SCA BOM) |

BOM import order in root `pom.xml` is significant: Boot → Spring Cloud → Spring Cloud Alibaba.
New dependencies for services must come from these BOMs — do not pin ad-hoc versions.

**Gateway starter coordinates** (Boot 3.5 / SC 2025 naming):
- `spring-cloud-starter-gateway-server-webflux` (NOT the legacy `spring-cloud-starter-gateway`).
- Sentinel gateway adapter: `com.alibaba.cloud:spring-cloud-alibaba-sentinel-gateway`
  (the old `sentinel-spring-cloud-gateway-adapter` is NOT managed by the SCA BOM).

---

## Scenario: registering a servlet filter in product-services

### 1. Scope / Trigger
- Trigger: any `FilterRegistrationBean` in `product-cloud-common` or service modules. Infra integration.

### 2. Signatures
- `new FilterRegistrationBean<OncePerRequestFilter>(filter)` — the bean name of the
  registration defaults to the `@Bean` method name; the FILTER chain name defaults to the
  class-derived name (`requestContextFilter` for `RequestContextFilter`).

### 3. Contracts
- Custom filter registrations MUST set an explicit, product-prefixed chain name:
  `registration.setName("productRequestContextFilter")`
  (see `ProductCloudCommonAutoConfiguration`).

### 4. Validation & Error Matrix
- Chain name collides with a Boot auto-configured filter name (e.g. `requestContextFilter`,
  which `WebMvcAutoConfiguration` in Boot 3.5 registers itself) -> duplicate/abstract filter
  resolution failure: service crashes at real Tomcat startup.
- MockMvc (`@SpringBootTest` + MockMvc) does NOT go through the servlet filter chain the same
  way -> this crash is INVISIBLE in MockMvc tests. Only a real boot (fat-jar or
  `SpringApplicationBuilder`... with real servlet container) catches it.

### 5. Good/Base/Bad Cases
- Good: `productRequestContextFilter` (explicit, prefixed).
- Base: Boot-provided filters keep their default names; never re-register them.
- Bad: relying on the default filter-chain name for a custom filter.

### 6. Tests Required
- A `@SpringBootTest` smoke test that starts the real web application context (all service
  modules already have `ApplicationTests`); plus at least one live-boot run per phase that adds
  filters (real JVM, real Tomcat) before marking a phase validated.

### 7. Wrong vs Correct
#### Wrong
```java
@Bean
public FilterRegistrationBean<RequestContextFilter> requestContextFilter() { ... } // default name clash
```
#### Correct
```java
@Bean
public FilterRegistrationBean<RequestContextFilter> requestContextFilterRegistration(Filter filter) {
    FilterRegistrationBean<RequestContextFilter> reg = new FilterRegistrationBean<>(filter);
    reg.setName("productRequestContextFilter");
    ...
}
```

---

## Distributed Tracing

**Rule**: trace propagation is owned by framework-native mechanisms only.

- Services: `RequestContextFilter` (product-cloud-common) resolves `X-Trace-Id` → W3C
  `traceparent` → generated UUID, and SYNTHESIZES a `traceparent` header when absent so the
  Micrometer server span, MDC, and the `X-Trace-Id` response header share one traceId. Do not
  bypass it by writing MDC alone — the server span will overwrite MDC with a fresh traceId and
  logs/headers will diverge (Phase 1 live-tested failure mode).
- Gateway: Spring Cloud Gateway's native Micrometer propagation is the ONLY propagation
  mechanism. Do NOT add a custom global filter that injects `traceparent`/`X-Trace-Id` — a
  previous `GatewayTraceFilter` produced double traceIds and was deleted. (Phase 1 live-tested.)

Tests: the observability smoke check per phase asserts
`service JSON log traceId == X-Trace-Id response header == MDC/span traceId`.

---

## Nacos (v3) infra contract

- compose healthcheck MUST use `http://127.0.0.1:8080/v3/console/health/readiness`
  (container-internal port). The v1 readiness endpoint returns **410 Gone** on Nacos v3 —
  a v1-based healthcheck makes the container permanently `unhealthy` while running fine.
- Namespace/group/Data ID conventions: see `product-services/README.md`
  (namespace `dev`; discovery group `PRODUCT_GROUP`; config `product-common.yml`@`PRODUCT_COMMON`
  + `product-<service>.yml`@`PRODUCT_<SERVICE>`). New services must follow it, no exceptions.
- Nacos registration names are the service names used in gateway `lb://` routes; the demand
  module's Maven artifactId is `product-demand-service` (avoids coordinate clash with the
  monolith module) while its Nacos name stays `product-demand`.

---

## Environment Gotchas (local dev machine)

> **Warning**: mise default JDK 17.0.2 crashes under cgroup v2
> (`ProcessorMetrics` — known old-patch JDK bug) when starting actuator-enabled apps.
> Use temurin 17.0.20+ locally for live runs (`mise use java:temurin-17` or equivalent).
> CI pins temurin 17 (latest patch) and is unaffected.

---

## product-services code conventions (differ from monolith)

- Use **constructor injection** for new platform code (monolith legacy uses `@Autowired` field
  injection; do not copy that style into `product-services`). Exception: classes ported
  byte-faithfully from the monolith keep their original style — contract fidelity wins there.
- Service skeletons carry a `@SpringBootTest` + MockMvc smoke test per module
  (`ApplicationTests`), runnable offline with Nacos disabled; keep them green in CI
  (`.github/workflows/build.yml` runs `mvn -B -ntp package` for all modules).
- Shared service code goes in `product-cloud-common` (auto-configured via
  `META-INF/spring/...AutoConfiguration.imports`) — never into the monolith's `product-common`.
- Error contract (`AjaxResult`/`ApiStatus`/`ServiceException` + `GlobalServiceExceptionHandler`)
  is byte-compatible with the monolith contract documented in
  [error-handling.md](./error-handling.md); validation-error and AccessDenied messages must stay
  identical so frontend behavior is unchanged across the migration.

---

## Per-service migration recipe (established in Phase 2, identity)

Every monolith domain migrated into `product-services/<service>` must include:

1. **Security wiring**: `product-cloud-security` dependency. The auto-config is
   secure-by-default (`product.security.enabled` conditional). Tests that must run offline opt
   out with `product.security.enabled=false` PLUS
   `spring.autoconfigure.exclude` of `SecurityAutoConfiguration`,
   `SecurityFilterAutoConfiguration`, `UserDetailsServiceAutoConfiguration`,
   `ManagementWebSecurityAutoConfiguration` — otherwise Boot's default servlet security chain
   401s every MockMvc request.
2. **Direct-port protection test**: one `*DirectPortSecurityTest` per service asserting
   `GET /skeleton/info` without a token returns the byte-exact monolith 401 body
   (`{"msg":"请求访问：/skeleton/info，认证失败，无法访问系统资源","code":401}`).
   This is the anti-spoofing guarantee: no client can bypass the gateway.
3. **SQL exception handling**: port the monolith's
   `@ExceptionHandler({SQLException, DataAccessException, PersistenceException,
   MyBatisSystemException})` + `getSqlErrorMessage` into the SERVICE module (see identity's
   `GlobalSqlExceptionHandler`), not cloud-common — the mybatis/spring-tx class literals in
   `@ExceptionHandler` crash startup of DB-less modules (same introspection-failure class as
   the filter-name gotcha above). Messages must be byte-identical to the monolith handler
   (duplicate entry → `数据已存在，请检查重复数据`, etc.).
4. **Schema init script** (`src/main/resources/db/init/<service>_schema.sql`): DDL must be
   byte-verbatim from root `schema.sql` INCLUDING the `AUTO_INCREMENT=N` table options;
   `CREATE DATABASE/USER IF NOT EXISTS` + `DROP TABLE IF EXISTS` reset semantics;
   dedicated per-service account with DML-only grants on its own database, nothing else.
   Any env var the script or `application.yml` references must exist in `.env.example`.
5. **Redis namespace**: key prefix `<service>:` (identity: `identity:captcha_codes:` etc.),
   shared Redis instance (ADR-0005 §4).

## Cross-domain reads: local query + contract enrichment (never JOINs)

Every service's MyBatis XML must reference ONLY tables in its own schema. When a monolith query
joined another domain's tables for display fields (e.g. planning's `/pps/batch/list` joining
`order_line`/`customer_order`/`product`), port it as: local paged query on the service's own
tables + batch-contract enrichment for the foreign fields (one demand-api + one master-data-api
call per page — never per-row). On contract failure, degrade the FOREIGN fields only
(null + warn log), never fail the whole local page. Record any intentional field-set deviation
from the monolith in the task's execution record. (Phase 4 lesson: a copied join compiled fine
and passed unit tests with mocked mappers, but 1146'd at runtime — and was once falsely
recorded as "200 OK"; live-probe transcripts go in scratch/<phase>/.)

## Event baseline (Phase 5, ADR-0004)

Event infrastructure lives in `product-cloud-messaging` (envelope v1, outbox relay, idempotent
consumer support, DLX audit); frozen topology + semantics table is in `product-services/README.md`,
design contract in the task's ADR-0004. Key pitfalls:
- NEVER publish envelopes via `rabbitTemplate.convertAndSend(byte[])` — the Jackson converter
  Base64-encodes byte[] bodies and consumers fail conversion (Phase 5 live-found defect; raw
  `Message` with JSON bytes + `application/json` + eventId/eventType headers is the only
  supported publish path; see OutboxRelay).
- ADR §4's "手动 ack" is implemented as `AcknowledgeMode.AUTO` + per-message ack-after-success
  interceptor (documented equivalent) — do not "simplify" to plain AUTO without the interceptor.
- Service-owned infra tables (`event_outbox`, `consumed_event`, `dead_letter_audit`, `ops_audit`,
  domain version counters) are documented non-baseline additions in each service's schema script;
  business tables stay byte-verbatim from root schema.sql.

## Repo gotcha: `*.sql` is gitignored by a legacy rule

`.gitignore` carries `*.sql` with only `!schema.sql` historically whitelisted — the five
service init scripts and `deploy/mysql/init/010-schema.sql` were silently untracked for six
phases (fresh clones / CI could not initialize service DBs). Whitelist now covers
`!product-services/*/src/main/resources/db/init/*.sql` and `!deploy/mysql/init/*.sql`.
When adding NEW source-tree SQL artifacts, check `git check-ignore` or extend the whitelist
explicitly; never rely on `git add -A` to pick up a new `.sql` file.

## Adding a new collaborative resource type (pattern, established by FIXTURE)

Adding a resource type that participates in scheduling (FIXTURE, Phase fixture-scheduling) is a
six-touchpoint additive change — follow it for future types instead of inventing shortcuts:

1. Constants: `RESOURCE_TYPE_X` in planning + master-data `ResourceConstants`; schema column
   comments.
2. master_data_db: extension table keyed by `resource.resource_id` (mirror `machine`/`mold`);
   aggregate write path writes resource row + extension row in ONE `@Transactional` and calls
   `MasterDataVersionService.bump()` in-transaction (drift guard depends on it). Compatibility
   data = dedicated allow-list table mirroring `machine_mold_compatibility`; note compatibility
   rows historically have NO master-data write path — new compat entries need an explicit
   maintenance method.
3. Contract: nested `XDTO` on `ResourceDTO` (no persistence annotations), loaded in the SAME
   single batch query pass in `InternalMasterDataController.getResources` (one IN query per
   extension, attach only for the matching resourceType — orphan-row defense).
4. Planning model: `domain/model/X.java` + nullable `Resource.x`; `SchedulingSnapshotLoader`
   maps it inside the existing fixture/machine branch pattern; AVAILABLE filter and drift guard
   untouched.
5. Requirements: new `RouteEligibleResourceRule` implementation (Spring auto-registers in
   `RouteRuleRegistry`) emitting mandatory rows with `resourceId=null`; legacy rules stay
   byte-identical, pinned by regression tests; add a `default` capability method on the
   interface if the calculator needs a signal.
6. Calculator: extend the existing explicit per-type structure (ResourceChoice field, selection
   stage in the machine branch, main-loop map/update blocks, `resolveSelectedResources` branch)
   — no default-allow on missing compatibility data, failure reuses the existing
   resource-unavailable ServiceException path verbatim, persistence stays on the generic
   requirement path (verify by test before patching anything).

Warning learned twice now: record numbers in the task's implement.md execution record
(test counts, wiring claims, diff bookkeeping) MUST come from a clean `mvn clean test` run
and committed scripts — stale target/ reports and edit scripts that print success without
verifying have produced false records in Phases 1–2; a live-record with no matching log entry
surfaced in Phase 4. If a claim isn't in a saved probe/log transcript, don't write it.
