# AuditEvent Implementation Guide (Partitioned + OAuth/SMART)

## Purpose

This guide defines how to add `AuditEvent` support in this fork of `hapi-fhir-jpaserver-starter` so that:

- Audit records are created in the same server (not forwarded to another server).
- Tenant/partition boundaries are preserved.
- Recursive audit creation is prevented.
- OAuth/SMART users are restricted so only approved roles/scopes can read/search `AuditEvent`.
- App launches (Sapphire Facet and Sapphire Engage) are auditable.
- REST activity on hosted FHIR servers is auditable.

## Jobs To Be Done

- When a provider launches a Sapphire Facet app, create an audit record for operations/privacy/security analysis.
- When a patient launches a Sapphire Engage app, create an audit record for operations/privacy/security analysis.
- When a REST request is sent to one of our hosted FHIR servers, create an audit record for operations/privacy/security analysis.

## Background and Context

- Mixpanel analytics does not satisfy PHI/security auditing needs.
- Grafana/system logs are operational telemetry, not structured PHI access audits.
- We now store cached/computed PHI on hosted servers and need our own audit trail.
- We still rely on EHR-owned FHIR servers to audit access to EHR-hosted data.
- Customers explicitly require app-launch audit records now.

## Goals

- Log launches for Sapphire Engage and Sapphire Facet apps.
- Log activities on hosted FHIR servers.
- Keep audit data available to internal security/admin teams and internal tools.

## Requirements

- **Standardized schema:** Use FHIR R4 `AuditEvent`.
- **Multi-tenancy:** Persist in the same server and tenant partition as the triggering activity.
- **Scope:** Record all app launches and hosted FHIR RESTful activities (excluding metadata/health/non-FHIR operational endpoints).
- **Privacy/security constraints:**
  - Do not include PHI names or query strings in persisted audit records.
- Use identifier-based references (`Reference.identifier`) for provider/patient context in Phase 1 launch events.
  - `user_role`, `audit_role` and `admin_role` can create `AuditEvent`.
  - `admin_role` and `audit_role` can read/search/history `AuditEvent`.
  - Deny external `PUT/PATCH/DELETE` on `AuditEvent`.
- **Stability constraints:**
  - Prevent recursion (audit-on-audit loops).
  - Fail-open on audit persistence errors.

## Current Fork Notes

- The server already wires:
  - `RequestTenantPartitionInterceptor` for partitioned mode.
  - OAuth authorization via `OAuthAuthorizationInterceptor`.
  - `SmartWellKnownInterceptor` for the SMART discovery metadata endpoint (`/.well-known/smart-configuration`), not for FHIR resource data-access control.
- Runtime behavior is controlled by `application.yaml` (or environment-overridden equivalent).
- This guide assumes your deployed `application.yaml` has partitioning enabled under:
  - `hapi.fhir.partitioning.*`

## Deployment Model (Important)

This repository builds the base Docker image only. The effective runtime configuration is supplied by a downstream deployment project.

What this means:

- Code changes for `AuditEvent` behavior belong in this repository.
- Runtime policy values (roles, scope naming, partition toggles, URLs, env var bindings) are controlled in the downstream `application.yaml`.
- Validation must include both:
  - image-level behavior tests from this repo, and
  - environment-level authorization/partition tests in the downstream deployment.

## Documentation Reviewed

- HAPI FHIR starter docs and extension points:
  - [README](https://github.com/hapifhir/hapi-fhir-jpaserver-starter/blob/master/README.md)
- HAPI BALP and interceptor docs:
  - [Basic Audit Log Pattern (BALP)](https://hapifhir.io/hapi-fhir/docs/security/balp_interceptor.html)
  - [Server Interceptors](https://hapifhir.io/hapi-fhir/docs/interceptors/server_interceptors.html)
  - [Built-In Interceptors](https://hapifhir.io/hapi-fhir/docs/interceptors/built_in_server_interceptors.html)
- HAPI partitioning docs:
  - [Partitioning and Multitenancy](https://hapifhir.io/hapi-fhir/docs/server_jpa_partitioning/partitioning.html)
  - [Partition Interceptor Examples](https://hapifhir.io/hapi-fhir/docs/server_jpa_partitioning/partition_interceptor_examples.html)
- HL7 R4 AuditEvent:
  - [AuditEvent](https://hl7.org/fhir/R4/auditevent.html)

## URI Semantics: Extensions vs Systems

Use the correct URI type for the correct purpose:

- **FHIR extension URL** (`Extension.url`):
  - Declares an extension definition (for example those listed in your app extension catalog).
- **Identifier system URI** (`Identifier.system`):
  - Names the namespace for identifier values (for example app IDs and service IDs used in audit identities).

For this audit design:

- `agent.who.identifier.system`, `entity.what.identifier.system`, and `source.observer.identifier.system` are **identifier system URIs**, not extension URLs.
- `Identifier.system` MUST be the authority URI of the system that issued the identifier value (not the local FHIR server URL).
  - If IDs are issued by an external FHIR server/EMR/MPI, use that authority's namespace URI.
  - If IDs are issued by Elimu-owned namespaces, `https://elimu.io/systems/...` is appropriate.
- `https://elimu.io/systems/apps` and `https://elimu.io/systems/services` in this guide are example/internal namespaces, not a universal requirement for all producer/provider/patient IDs.

Naming convention note:

- `entity.detail.type` values are local keys, not URLs. Prefer kebab-case for consistency (`launch-mode`).

## AuditEvent Schema (R4) for This Use Case

This section describes how Phase 1 app-launch events are persisted as R4 `AuditEvent`.

### Minimum R4 Shape (must be present)

From the R4 resource definition, these elements are required:

- `AuditEvent.type` (1..1)
- `AuditEvent.recorded` (1..1)
- `AuditEvent.agent` (1..*)
- `AuditEvent.agent.requestor` (1..1 for each agent)
- `AuditEvent.source` (1..1)
- `AuditEvent.source.observer` (1..1)

Important constraint:

- `AuditEvent.entity`: `name` and `query` must not both be populated (`sev-1` invariant).

### App Launch Mapping (Phase 1)

For launch events, persist one `AuditEvent` where:

- **Event type decision (explicit)**
  - Selected: `type = 110100` from `http://dicom.nema.org/resources/ontology/DCM` (`Application Activity`).
  - Why selected: this event is an application workflow event (SMART launch), not primarily a server-side CRUD/query audit record.
  - Not selected for Phase 1:
    - `rest` (FHIR RESTful Operation): better fit for Phase 2 BALP/server-generated interaction audits.
    - `110112` (Query): too narrow; launch is not a pure query event.
    - `110110` (Patient Record): too broad and implies direct patient-record CRUD/access semantics instead of app launch semantics.
    - ISO 21089 lifecycle codes (e.g., `access`, `originate`, `report`): lifecycle-oriented and less specific than `Application Activity` for this use case.
  - Required companion for precision:
    - `subtype = 110120` (`Application Start`) for launch events.

- **Event family**
  - `type`: `Application Activity` (`110100`).
  - `subtype`: app-launch specific code (local or standard code where available).
  - `action`: typically `E` (execute).
- **When**
  - `recorded`: server-side timestamp when event is persisted.
- **Who (provider/requestor)**
  - For provider-launched Facet flows: `agent[0].who` uses `Reference.identifier` (not a resolvable local FHIR reference).
  - For patient-launched Engage flows: `agent[0].who` uses `Reference.identifier` (not a resolvable local FHIR reference).
  - `agent[0].altId` and/or `agent[0].name`: token-derived identity if canonical FHIR subject reference is not available.
  - `agent[0].requestor`: `true`.
- **Which app participated**
  - `agent[1]`: the SMART app/backend actor (`requestor: false`).
  - `agent[1].who.identifier`: stable app identity (for example OAuth `client_id` namespace + value).
  - Optional `agent[1].who.display`: human-readable app name.
  - Optional `agent[1].altId`: duplicate app identifier for systems that index `altId`.
- **What patient context**
  - Patient/facet mode: include `entity[0].what.identifier` with stable patient identity.
  - Roster mode: omit patient reference.
  - Use `entity[0].detail` to include `launch-mode` (`facet` or `roster`).
  - These patient identities are launch-context references and may point to patients not stored in this FHIR server.
- **What app/launch context**
  - Optional additional app launch details in `entity[1].detail[]`: launch/session identifiers, SMART context values, trace headers.
  - Keep `entity[1].type` only if needed for analytics; otherwise omit for a lean canonical payload.
- **Which system recorded it**
  - `source.observer` may be the same app identified in `agent[1]`.
  - Prefer `source.observer.identifier` with a stable app/system identifier (reference-by-identifier pattern).
  - Optional `source.observer.display`: human-readable app/system name.
- **Optional network evidence**
  - `agent[1].network.address` + `agent[1].network.type` can be included when source IP is trusted and operationally useful.
  - In load-balanced/proxied deployments, only log client IP if your edge reliably normalizes trusted forwarding headers.
- **No PHI names or query capture**
  - Do not populate patient names in `entity.what.display`.
  - Do not populate `AuditEvent.entity.query` for this use case.

### Launch Mode Differentiation

- **Facet roster mode (population view):**
  - Omit `entity.what` patient reference.
  - Include `entity.detail` with `launch-mode = roster`.
- **Facet mode (patient view):**
  - Include `entity.what.identifier` with patient identity.
  - Include `entity.detail` with `launch-mode = facet`.
- **Engage patient launches:**
  - No roster mode.
  - Requestor is the patient actor.
  - Include `entity.what.identifier` for patient context.
  - `launch-mode` is optional for Engage and not required for acceptance.

### Example JSON (Roster Launch / Population View)

Note: Example `identifier.system` values below are illustrative. In production, use the identifier authority URI that actually issues each ID (external partner authority when applicable).

```json
{
  "resourceType": "AuditEvent",
  "type": {
    "system": "http://dicom.nema.org/resources/ontology/DCM",
    "code": "110100",
    "display": "Application Activity"
  },
  "subtype": [
    {
      "system": "http://dicom.nema.org/resources/ontology/DCM",
      "code": "110120",
      "display": "Application Start"
    }
  ],
  "action": "E",
  "recorded": "2026-03-31T15:30:00Z",
  "outcome": "0",
  "agent": [
    {
      "type": {
        "coding": [
          {
            "system": "http://terminology.hl7.org/CodeSystem/extra-security-role-type",
            "code": "humanuser"
          }
        ]
      },
      "who": {
        "identifier": {
          "system": "https://elimu.io/systems/providers",
          "value": "provider-789"
        }
      },
      "requestor": true
    },
    {
      "type": {
        "coding": [
          {
            "system": "http://dicom.nema.org/resources/ontology/DCM",
            "code": "110150",
            "display": "Application"
          }
        ]
      },
      "who": {
        "identifier": {
          "system": "https://elimu.io/systems/apps",
          "value": "hypertension-web"
        },
        "display": "Sapphire Hypertension Facet"
      },
      "altId": "client-id-xyz-123",
      "requestor": false
    }
  ],
  "source": {
    "observer": {
      "identifier": {
        "system": "https://elimu.io/systems/services",
        "value": "audit-services"
      },
      "display": "Sapphire Audit Services"
    }
  },
  "entity": [
    {
      "type": {
        "system": "http://terminology.hl7.org/CodeSystem/audit-entity-type",
        "code": "2",
        "display": "System Object"
      },
      "detail": [
        {
          "type": "launch-mode",
          "valueString": "roster"
        },
        {
          "type": "X-Request-Id",
          "valueString": "req-abc-123"
        },
        {
          "type": "X-Correlation-Id",
          "valueString": "corr-xyz-789"
        }
      ]
    }
  ]
}
```

### Example JSON (Facet Launch / Patient View)

```json
{
  "resourceType": "AuditEvent",
  "type": {
    "system": "http://dicom.nema.org/resources/ontology/DCM",
    "code": "110100",
    "display": "Application Activity"
  },
  "subtype": [
    {
      "system": "http://dicom.nema.org/resources/ontology/DCM",
      "code": "110120",
      "display": "Application Start"
    }
  ],
  "action": "E",
  "recorded": "2026-03-31T15:35:00Z",
  "outcome": "0",
  "agent": [
    {
      "type": {
        "coding": [
          {
            "system": "http://terminology.hl7.org/CodeSystem/extra-security-role-type",
            "code": "humanuser"
          }
        ]
      },
      "who": {
        "identifier": {
          "system": "https://elimu.io/systems/providers",
          "value": "provider-789"
        }
      },
      "requestor": true
    },
    {
      "type": {
        "coding": [
          {
            "system": "http://dicom.nema.org/resources/ontology/DCM",
            "code": "110150",
            "display": "Application"
          }
        ]
      },
      "who": {
        "identifier": {
          "system": "https://elimu.io/systems/apps",
          "value": "hypertension-web"
        },
        "display": "Sapphire Hypertension Facet"
      },
      "altId": "client-id-xyz-123",
      "requestor": false
    }
  ],
  "source": {
    "observer": {
      "identifier": {
        "system": "https://elimu.io/systems/services",
        "value": "audit-services"
      },
      "display": "Sapphire Audit Services"
    }
  },
  "entity": [
    {
      "what": {
        "identifier": {
          "system": "https://elimu.io/systems/patients",
          "value": "patient-456"
        }
      },
      "type": {
        "system": "http://terminology.hl7.org/CodeSystem/audit-entity-type",
        "code": "1",
        "display": "Person"
      },
      "role": {
        "system": "http://terminology.hl7.org/CodeSystem/object-role",
        "code": "1",
        "display": "Patient"
      },
      "detail": [
        {
          "type": "launch-mode",
          "valueString": "facet"
        },
        {
          "type": "X-Request-Id",
          "valueString": "req-def-456"
        },
        {
          "type": "X-Correlation-Id",
          "valueString": "corr-xyz-789"
        }
      ]
    }
  ]
}
```

### Example JSON (Engage Patient Launch)

```json
{
  "resourceType": "AuditEvent",
  "type": {
    "system": "http://dicom.nema.org/resources/ontology/DCM",
    "code": "110100",
    "display": "Application Activity"
  },
  "subtype": [
    {
      "system": "http://dicom.nema.org/resources/ontology/DCM",
      "code": "110120",
      "display": "Application Start"
    }
  ],
  "action": "E",
  "recorded": "2026-03-31T15:40:00Z",
  "outcome": "0",
  "agent": [
    {
      "type": {
        "coding": [
          {
            "system": "http://terminology.hl7.org/CodeSystem/extra-security-role-type",
            "code": "humanuser"
          }
        ]
      },
      "who": {
        "identifier": {
          "system": "https://elimu.io/systems/patients",
          "value": "patient-456"
        }
      },
      "requestor": true
    },
    {
      "type": {
        "coding": [
          {
            "system": "http://dicom.nema.org/resources/ontology/DCM",
            "code": "110150",
            "display": "Application"
          }
        ]
      },
      "who": {
        "identifier": {
          "system": "https://elimu.io/systems/apps",
          "value": "engage-web"
        },
        "display": "Sapphire Engage"
      },
      "altId": "client-id-engage-123",
      "requestor": false
    }
  ],
  "source": {
    "observer": {
      "identifier": {
        "system": "https://elimu.io/systems/services",
        "value": "audit-services"
      },
      "display": "Sapphire Audit Services"
    }
  },
  "entity": [
    {
      "what": {
        "identifier": {
          "system": "https://elimu.io/systems/patients",
          "value": "patient-456"
        }
      },
      "type": {
        "system": "http://terminology.hl7.org/CodeSystem/audit-entity-type",
        "code": "1",
        "display": "Person"
      },
      "role": {
        "system": "http://terminology.hl7.org/CodeSystem/object-role",
        "code": "1",
        "display": "Patient"
      },
      "detail": [
        {
          "type": "X-Request-Id",
          "valueString": "req-eng-001"
        },
        {
          "type": "X-Correlation-Id",
          "valueString": "corr-eng-001"
        }
      ]
    }
  ]
}
```

Notes:

- The provider and patient are both first-class in this shape:
  - provider in `agent[*]`
  - patient in `entity[*].what` (and searchable via the `patient` search parameter behavior in R4).
- For Engage launches, patient is also the requestor actor (`agent[0].who.identifier` carries patient identity).
- The app can be both:
  - an event participant (`agent[*]`), and
  - the reporting observer (`source.observer`).
- `source.observer.identifier` and `agent[1].who.identifier` should use the same stable identifier when they represent the same app/system.
- Roster/facet differentiation applies to Facet launches and is represented by entity context (`entity.what` present vs omitted) plus explicit `launch-mode` detail.
- There are no patient-app roster launches.
- Payloads should avoid PHI names and query blobs for this use case.
- Phase 1 accepts any valid R4 `AuditEvent`, but this mapping is the recommended canonical shape for app-launch records to keep downstream analytics consistent.

## Recommended Design

## 1) Generate AuditEvent Locally in the Same Server

Use two paths to persist `AuditEvent` inside the same JPA server process:

- Phase 1: normal REST `POST /AuditEvent` ingestion (authorized by `OAuthAuthorizationInterceptor` rules).
- Phase 2: HAPI BALP interceptor/classes for server-side audit capture.

Preferred approach for this fork:

- Do not build a custom pointcut-based audit generator for Phase 2 unless a BALP gap is identified.
- Use HAPI BALP capture for supported BALP events.
- Keep storage local to this server (no remote audit endpoint requirement).

Why:

- Meets your requirement to keep events in-server.
- Avoids external network dependencies and delivery lag.
- Keeps partitioning and transaction semantics under your control.

## 2) Partition/Tenant Placement Strategy

### Decision

Create `AuditEvent` in the same tenant/partition as the triggering resource access.

### Rationale

- Matches tenant data isolation expectations.
- Lets tenant-level operations audit their own access.
- Keeps partition-aware search and retention policies aligned.

### Implementation Notes

- Use request tenant context (same source as your `RequestTenantPartitionInterceptor`) when creating the `AuditEvent`.
- With partitioning enabled, requests are tenant-qualified by URL and operations are executed in that tenant context.
- `AuditEvent` records follow the same tenant context as the triggering request/resource.

## 3) Recursion Prevention (AuditEvent Access Should Not Re-Audit)

This is required.

Implement two guards:

1. **Resource-type guard**
   - If target resource is `AuditEvent`, skip audit generation.
2. **Internal-write guard**
   - Set a request/thread marker while persisting internally generated `AuditEvent`.
   - If marker is present, skip generation.

Without these guards, reads/searches on `AuditEvent` can self-trigger indefinitely (or at least generate noisy audit-on-audit records).

## 4) OAuth/SMART Access Restrictions for AuditEvent

Not all bearer tokens should read audit logs.

### Decision

Use a two-phase authorization model:

- **Phase 1:** any authenticated token holder (existing non-admin client/user role) may create `AuditEvent` using `POST /AuditEvent`.
- **Phase 2:** BALP-generated `AuditEvent` entries are added internally by interceptor hooks.
- In all phases, `AuditEvent` read/search/history is restricted to `admin_role`.

### Implementation Notes

- Update `OAuthAuthorizationInterceptor` rules to explicitly handle `AuditEvent` before broad allow rules.
- Enforce `admin_role` for all `AuditEvent` read/search/history interactions.
- Allow authenticated external clients to create `AuditEvent` via `POST` only.
- Producer trust for Phase 1 relies on existing token validation in `OAuthAuthorizationInterceptor` (single allowed client_id/audience).
- Deny external `PUT`, `PATCH`, and `DELETE` on `AuditEvent` (preserve audit integrity).
- Allow system-internal interceptor path to create `AuditEvent` (BALP/internal capture).

### AuditEvent Auth Matrix

- `POST /AuditEvent`: allow authenticated token holders (Phase 1) and internal interceptor writes.
- `GET /AuditEvent`, `GET /AuditEvent/{id}`, history/search: `admin_role` only.
- `PUT/PATCH/DELETE /AuditEvent`: deny non-internal callers.

### Why this matters

HL7 guidance treats AuditEvent as security/privacy log data intended for admins/privacy officers, not general clinical users.

## Downstream Runtime Config Checklist

Based on your provided runtime `application.yaml`, this section captures what is already aligned and what still needs explicit `AuditEvent` policy wiring.

### Already aligned in your runtime config

- Partitioning is enabled:
  - `hapi.fhir.partitioning.allow_references_across_partitions: false`
  - `hapi.fhir.partitioning.partitioning_include_in_search_hashes: false`
- OAuth support is enabled via runtime env:
  - `hapi.fhir.oauth.enabled: ${OAUTH_ENABLED:false}`
  - role/env placeholders for `user_role` and `admin_role` are already present.
- SMART endpoints are configured under `hapi.fhir.smart.*`.
- ID strategy is tenant-safer than sequential numeric:
  - `hapi.fhir.client_id_strategy: ANY`
  - `hapi.fhir.server_id_strategy: UUID`

### Still required for AuditEvent rollout

- Add explicit authorization rules in code so:
  - only audit-privileged principals can read/search/history `AuditEvent`,
  - authenticated external callers can create `AuditEvent` via `POST`,
  - external mutation (`PUT/PATCH/DELETE`) is blocked,
  - interceptor-internal writes are allowed.
- Implement the policy "audit-privileged principal == token with `admin_role`".
- Audit persistence failure behavior is fail-open:
  - business operation must succeed even if audit write fails.

## Implementation Plan (Phased)

## Phase-to-Goal Mapping

- **Phase 1** delivers app launch logging goals (Sapphire Engage + Sapphire Facet launch events).
- **Phase 2** delivers hosted FHIR server activity logging goals (BALP/interceptor capture).

## BALP Scope for Phase 2

Phase 2 implements BALP behavior only, using what HAPI BALP supports out of the box.

Included in scope:

- BALP profiles/categories emitted by HAPI for hosted FHIR REST server activity (for example: create/read/update/delete/search/query, including patient vs non-patient variants where applicable).

Out of scope for Phase 2:

- custom audit behavior beyond BALP,
- non-FHIR endpoints (actuator and OAuth token/introspection/revocation endpoints),
- adding non-BALP event categories not provided by HAPI BALP.

## Phase 1: Non-BALP AuditEvent Ingest (+ tests)

- Add explicit auth rules allowing authenticated token holders to `POST /AuditEvent`.
- Use existing interceptor token constraints (single client_id/audience) as producer trust control.
- Keep `AuditEvent` read/search/history admin-only.
- Deny external `PUT/PATCH/DELETE` on `AuditEvent`.
- Validate creation in correct tenant/partition using existing partition interceptor behavior.
- Phase 1 producers are:
  - specialized backend microservice(s),
  - SMART-on-FHIR app backend path invoked by web frontend apps.
- Require minimum payload content for app-launch `AuditEvent` creates:
  - tenant identifier (via request tenant context),
  - actor identity derived from token context,
  - app identifier and launch context (Engage/Facet + launch mode),
  - event timestamp,
  - request tracing headers when present (`X-Request-Id`, `X-Correlation-Id`).
- Add tests for:
  - allowed `POST /AuditEvent` with valid token,
  - roster launch persists without `Patient` entity reference,
  - facet launch persists with identifier-based patient context in `entity.what.identifier`,
  - launch-mode detail is persisted (`roster`/`facet`),
  - Engage patient launch persists with patient requestor actor identity in `agent[0].who.identifier`,
  - no PHI names/query blobs are persisted for launch events,
  - denied `PUT/PATCH/DELETE /AuditEvent`,
  - denied non-admin reads of `AuditEvent`,
  - admin-only read/search/history of `AuditEvent`.

## Phase 2: BALP/Internal Capture Use Cases (+ tests)

- Add BALP capture using HAPI-supported BALP implementation only.
- Persist BALP-generated `AuditEvent` locally in this server.
- Ensure recursion guards prevent audit-on-audit loops.
- Keep Phase 1 auth matrix unchanged.
- Add tests for:
  - tenant A operations create `AuditEvent` only in tenant A.
  - tenant B cannot read tenant A `AuditEvent`.
  - reading/searching `AuditEvent` does not create new `AuditEvent`.
- Add metrics/logging for audit creation failures (without blocking business operations).

## Scope Decisions (Confirmed)

- Audit persistence failures are fail-open.
- Phase 1 is the non-BALP ingest use case and includes tests.
- Retention/archival policy is out of scope for this implementation; audit records are retained indefinitely.
- There are no no-token access paths in scope; all relevant operations use authenticated OAuth tokens.
- AuditEvent storage location is the same hosted FHIR server and tenant as the triggering operation.
- External audit access policy is implemented via role-based OAuth checks:
  - authenticated token holders may `POST /AuditEvent`,
  - only `admin_role` may read/search/history `AuditEvent`,
  - non-internal callers may not `PUT/PATCH/DELETE /AuditEvent`.
- Launch audit reporter pattern is trusted witness:
  - `audit-services` microservice posts `AuditEvent` after validating launch context against OAuth token context.
- Tracing fields `X-Request-Id` and `X-Correlation-Id` are captured in `entity.detail` when present.

## Acceptance Criteria

- A valid authenticated non-admin token can `POST /AuditEvent`.
- Provider/patient launch events use `type = 110100` and `subtype = 110120`.
- Roster launch events omit `Patient` reference and include `launch-mode = roster`.
- Facet launch events include `entity.what.identifier` (patient identity) and `launch-mode = facet`.
- Engage launches are patient-launched only:
  - `agent[0].who.identifier` carries patient identity,
  - no roster launch mode.
- Launch payloads do not include patient names or `entity.query`.
- A non-admin token cannot read/search/history `AuditEvent`.
- An `admin_role` token can read/search/history `AuditEvent`.
- Non-internal callers cannot `PUT/PATCH/DELETE /AuditEvent`.
- With partitioned URLs, created `AuditEvent` records are persisted in the request tenant.
- BALP/internal capture events are created for hosted server REST interactions supported by HAPI BALP in Phase 2.
- Accessing `AuditEvent` does not recursively generate additional `AuditEvent` entries.

## Open Questions (Future Phases)

- Identity extraction for BALP/internal events when bearer tokens do not carry canonical provider/patient identifiers.
- BALP profile coverage expectations by environment (for example search vs delete emphasis).
- OAuth security token pattern detail for internal servers (`minimal` vs `comprehensive` pattern).

## Risks and Other Issues to Anticipate

- **Performance/storage growth:** read/search auditing can generate high volume quickly.
- **Authorization regression risk:** future changes to authorization rules could accidentally broaden `AuditEvent` access unless covered by tests.

## Suggested Next Step

Implement Phase 1 first (including tests), then implement Phase 2 and its partition/recursion tests.
