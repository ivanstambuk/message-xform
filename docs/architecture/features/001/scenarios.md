# Feature 001 – Transform Engine: Scenarios

| Field | Value |
|-------|-------|
| Status | Draft |
| Last updated | 2026-02-15 |
| Linked spec | `docs/architecture/features/001/spec.md` |
| Format | Data Transform (see `docs/architecture/spec-guidelines/scenarios-format.md`) |

> Guardrail: Each scenario is a **testable contract** expressed as structured
> YAML. Scenarios serve as integration test fixtures — they can be loaded or
> parsed directly by test infrastructure.

---

## Format

Each scenario follows this structure:


---

```yaml
scenario: S-001-XX
name: human-readable-name
description: What this scenario tests.
tags: [category, ...]

transform:
  lang: jslt          # or jolt, jq, jsonata
  expr: |
    { ... }

input: { ... }
expected_output: { ... }
```

For bidirectional scenarios, both `forward` and `reverse` are tested.
For error scenarios, `expected_error` replaces `expected_output`.
For multi-engine scenarios, the same input/output is tested with different engines.

---

## Category 1: PingAM Callback Transformation

Real-world use cases derived from PingAM 8 authentication API documentation.


---

### S-001-01: PingAM Callback — Username + Password Step

The core use case: transform PingAM's nested callback JSON into a clean, flat
structure suitable for a modern frontend.


---

```yaml
scenario: S-001-01
name: pingam-callback-username-password
description: >
  Transform PingAM callback response with NameCallback + PasswordCallback
  into a clean JSON structure for frontend consumption.
tags: [pingam, callback, structural, core]
refs: [FR-001-01]

transform:
  lang: jslt
  expr: |
    {
      "type": if (.callbacks) "challenge" else "simple",
      "authId": .authId,
      "stage": .stage,
      "fields": [for (.callbacks)
        {
          "label": .output[0].value,
          "fieldId": .input[0].name,
          "value": .input[0].value,
          "type":
            if   (.type == "NameCallback") "text"
            else if (.type == "PasswordCallback") "password"
            else "unknown",
          "sensitive": .type == "PasswordCallback"
        }
      ]
    }

input:
  authId: "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJvdGsiOiJ..."
  template: ""
  stage: "DataStore1"
  callbacks:
    - type: "NameCallback"
      output:
        - name: "prompt"
          value: " User Name: "
      input:
        - name: "IDToken1"
          value: ""
    - type: "PasswordCallback"
      output:
        - name: "prompt"
          value: " Password: "
      input:
        - name: "IDToken2"
          value: ""

expected_output:
  type: "challenge"
  authId: "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJvdGsiOiJ..."
  stage: "DataStore1"
  fields:
    - label: " User Name: "
      fieldId: "IDToken1"
      value: ""
      type: "text"
      sensitive: false
    - label: " Password: "
      fieldId: "IDToken2"
      value: ""
      type: "password"
      sensitive: true
```
---


### S-001-02: PingAM Callback — Reverse (Frontend → PingAM)

The reverse direction: transform the clean frontend JSON back into PingAM's
callback format, with filled-in values.


---

```yaml
scenario: S-001-02
name: pingam-callback-reverse
description: >
  Reverse transform: convert clean frontend JSON with filled values back into
  PingAM callback format for submission.
tags: [pingam, callback, reverse, bidirectional]
refs: [FR-001-01, FR-001-03]

transform:
  lang: jslt
  expr: |
    {
      "authId": .authId,
      "template": "",
      "stage": .stage,
      "callbacks": [for (.fields)
        {
          "type":
            if   (.type == "password") "PasswordCallback"
            else "NameCallback",
          "output": [{ "name": "prompt", "value": .label }],
          "input": [{ "name": .fieldId, "value": .value }]
        }
      ]
    }

input:
  type: "challenge"
  authId: "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJvdGsiOiJ..."
  stage: "DataStore1"
  fields:
    - label: " User Name: "
      fieldId: "IDToken1"
      value: "bjensen"
      type: "text"
      sensitive: false
    - label: " Password: "
      fieldId: "IDToken2"
      value: "Ch4ng31t"
      type: "password"
      sensitive: true

expected_output:
  authId: "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJvdGsiOiJ..."
  template: ""
  stage: "DataStore1"
  callbacks:
    - type: "NameCallback"
      output:
        - name: "prompt"
          value: " User Name: "
      input:
        - name: "IDToken1"
          value: "bjensen"
    - type: "PasswordCallback"
      output:
        - name: "prompt"
          value: " Password: "
      input:
        - name: "IDToken2"
          value: "Ch4ng31t"
```
---


### S-001-03: PingAM Success Response — Strip Internals

Transform PingAM success response to remove internal fields and normalize
the response for the frontend.


---

```yaml
scenario: S-001-03
name: pingam-success-strip-internals
description: >
  Transform PingAM authentication success response: keep tokenId, normalize
  realm, strip successUrl (internal AM URL not useful for frontend).
tags: [pingam, success, remove-fields]
refs: [FR-001-01]

transform:
  lang: jslt
  expr: |
    {
      "authenticated": true,
      "sessionToken": .tokenId,
      "realm": .realm
    }

input:
  tokenId: "AQIC5wM…TU3OQ*"
  successUrl: "/am/console"
  realm: "/alpha"

expected_output:
  authenticated: true
  sessionToken: "AQIC5wM…TU3OQ*"
  realm: "/alpha"
```
---


### S-001-04: PingAM Callback — Choice/Confirmation Types

More callback types: ChoiceCallback with multiple options, ConfirmationCallback.


---

```yaml
scenario: S-001-04
name: pingam-callback-choice-confirmation
description: >
  Transform PingAM callbacks with ChoiceCallback (select from list) and
  ConfirmationCallback (confirm action) into clean frontend fields.
tags: [pingam, callback, choice, structural]
refs: [FR-001-01]

transform:
  lang: jslt
  expr: |
    {
      "type": "challenge",
      "authId": .authId,
      "stage": .stage,
      "fields": [for (.callbacks)
        {
          "label": .output[0].value,
          "fieldId": .input[0].name,
          "value": .input[0].value,
          "type":
            if   (.type == "ChoiceCallback") "choice"
            else if (.type == "ConfirmationCallback") "confirm"
            else if (.type == "TextInputCallback") "text"
            else "unknown",
          "options": if (.type == "ChoiceCallback") .output[1].value else null
        }
      ]
    }

input:
  authId: "eyJ0eXAiOiJKV1Qi..."
  template: ""
  stage: "MFAChoice"
  callbacks:
    - type: "ChoiceCallback"
      output:
        - name: "prompt"
          value: "Choose authentication method"
        - name: "choices"
          value: ["SMS", "Email", "Push Notification"]
        - name: "defaultChoice"
          value: 0
      input:
        - name: "IDToken1"
          value: "0"
    - type: "ConfirmationCallback"
      output:
        - name: "prompt"
          value: "Continue?"
        - name: "options"
          value: ["Continue", "Cancel"]
      input:
        - name: "IDToken2"
          value: "0"

expected_output:
  type: "challenge"
  authId: "eyJ0eXAiOiJKV1Qi..."
  stage: "MFAChoice"
  fields:
    - label: "Choose authentication method"
      fieldId: "IDToken1"
      value: "0"
      type: "choice"
      options: ["SMS", "Email", "Push Notification"]
    - label: "Continue?"
      fieldId: "IDToken2"
      value: "0"
      type: "confirm"
      # Note: JSLT omits keys whose value is null — no `options` key here
```
---


### S-001-05: PingAM — No Callbacks (Simple Login Success)

Edge case: no callbacks at all (zero-page login result).


---

```yaml
scenario: S-001-05
name: pingam-no-callbacks-simple-success
description: >
  When PingAM returns a success response (no callbacks), the transform
  should correctly detect the non-challenge case.
tags: [pingam, edge-case, conditional]
refs: [FR-001-01]

transform:
  lang: jslt
  expr: |
    {
      "type": if (.callbacks) "challenge" else "success",
      "authId": .authId,
      "sessionToken": .tokenId,
      "realm": .realm
    }

input:
  tokenId: "AQIC5wM…TU3OQ*"
  successUrl: "/am/console"
  realm: "/alpha"

expected_output:
  type: "success"
  # Note: JSLT omits keys whose value is null — no `authId` key here
  sessionToken: "AQIC5wM…TU3OQ*"
  realm: "/alpha"
```

---

## Category 2: Gateway Pattern — Field Operations

Use cases derived from Kong/Apigee transformation patterns. These represent
common gateway transformations that message-xform should handle.


---

### S-001-06: Strip Internal/Debug Fields

Remove fields that should not be exposed to external consumers.


---

```yaml
scenario: S-001-06
name: strip-internal-fields
description: >
  Remove internal/debug fields from API response before exposing to consumer.
  Common gateway pattern: Kong remove operation equivalent.
tags: [gateway, remove, security, kong-pattern]
refs: [FR-001-01]

transform:
  lang: jslt
  expr: |
    {
      "id": .id,
      "name": .name,
      "email": .email,
      "role": .role
    }

input:
  id: "usr-42"
  name: "Bob Jensen"
  email: "bjensen@example.com"
  role: "admin"
  _internal_id: 99182
  _debug_trace: "svc=users,dur=12ms,node=us-east-1a"
  _db_version: 42
  password_hash: "$2b$12$LJ3m4…"

expected_output:
  id: "usr-42"
  name: "Bob Jensen"
  email: "bjensen@example.com"
  role: "admin"
```
---


### S-001-07: Strip Internal Fields with Open-World Passthrough

Same as above, but using JSLT's `* : .` to pass everything through EXCEPT
the blacklisted fields. This tests the open-world assumption.


---

```yaml
scenario: S-001-07
name: strip-internal-fields-passthrough
description: >
  Remove specific internal fields while passing through ALL other fields
  unchanged. Tests JSLT's object matching pattern (* : .).
tags: [gateway, remove, open-world, passthrough]
refs: [FR-001-01, NFR-001-04]

transform:
  lang: jslt
  expr: |
    {
      * : .
    }
    - "_internal_id"
    - "_debug_trace"
    - "_db_version"
    - "password_hash"

input:
  id: "usr-42"
  name: "Bob Jensen"
  email: "bjensen@example.com"
  role: "admin"
  _internal_id: 99182
  _debug_trace: "svc=users,dur=12ms,node=us-east-1a"
  _db_version: 42
  password_hash: "$2b$12$LJ3m4…"
  custom_field: "should be preserved"

expected_output:
  id: "usr-42"
  name: "Bob Jensen"
  email: "bjensen@example.com"
  role: "admin"
  custom_field: "should be preserved"
```
---


### S-001-08: Rename Fields (API Versioning)

Rename fields for API version migration without changing structure.


---

```yaml
scenario: S-001-08
name: rename-fields-api-versioning
description: >
  Rename fields for API version migration. Common gateway pattern:
  v1 used snake_case, v2 uses camelCase. Transform backend v1 to frontend v2.
tags: [gateway, rename, api-versioning, kong-pattern]
refs: [FR-001-01]

transform:
  lang: jslt
  expr: |
    {
      "userId": .user_id,
      "firstName": .first_name,
      "lastName": .last_name,
      "emailAddress": .email_address,
      "isActive": .is_active,
      "createdAt": .created_at
    }

input:
  user_id: "usr-42"
  first_name: "Bob"
  last_name: "Jensen"
  email_address: "bjensen@example.com"
  is_active: true
  created_at: "2025-01-15T10:30:00Z"

expected_output:
  userId: "usr-42"
  firstName: "Bob"
  lastName: "Jensen"
  emailAddress: "bjensen@example.com"
  isActive: true
  createdAt: "2025-01-15T10:30:00Z"
```
---


### S-001-09: Add Default Values

Enrich response with default values for missing fields.


---

```yaml
scenario: S-001-09
name: add-default-values
description: >
  Add default values for missing fields. Common gateway pattern:
  ensure consumer always gets a complete response shape.
tags: [gateway, defaults, kong-pattern]
refs: [FR-001-01]

transform:
  lang: jslt
  expr: |
    {
      "id": .id,
      "name": .name,
      "status": if (.status) .status else "unknown",
      "tier": if (.tier) .tier else "free",
      "metadata": if (.metadata) .metadata else {},
      * : .
    }

input:
  id: "usr-42"
  name: "Bob Jensen"

expected_output:
  id: "usr-42"
  name: "Bob Jensen"
  status: "unknown"
  tier: "free"
  metadata: {}
```
---


### S-001-10: Add Gateway Metadata

Inject gateway metadata into response body. Common pattern for traceability.


---

```yaml
scenario: S-001-10
name: add-gateway-metadata
description: >
  Inject gateway metadata into the response body for traceability.
  Like Kong's add operation but for the body structure.
tags: [gateway, enrichment, metadata]
refs: [FR-001-01]

transform:
  lang: jslt
  expr: |
    . + {
      "_gateway": {
        "transformedBy": "message-xform",
        "timestamp": now()
      }
    }

input:
  id: "ord-123"
  amount: 99.50
  currency: "EUR"

# Note: expected_output uses approximate match for timestamp
expected_output:
  id: "ord-123"
  amount: 99.50
  currency: "EUR"
  _gateway:
    transformedBy: "message-xform"
    # timestamp: dynamic — test should verify key exists, not exact value
```

---

## Category 3: Structural Reshaping

Deep structural transformations — the core differentiator of message-xform
vs field-level gateway transformers.


---

### S-001-11: Flatten Nested Object

Flatten a deeply nested object into a flat key-value structure.


---

```yaml
scenario: S-001-11
name: flatten-nested-object
description: >
  Flatten a deeply nested user profile into a flat structure for a
  simplified API consumer. Tests deep path access.
tags: [structural, flatten, deep-path]
refs: [FR-001-01]

transform:
  lang: jslt
  expr: |
    {
      "id": .id,
      "name": .profile.name.full,
      "firstName": .profile.name.first,
      "lastName": .profile.name.last,
      "email": .profile.contact.email,
      "phone": .profile.contact.phone,
      "city": .profile.address.city,
      "country": .profile.address.country
    }

input:
  id: "usr-42"
  profile:
    name:
      first: "Bob"
      last: "Jensen"
      full: "Bob Jensen"
    contact:
      email: "bjensen@example.com"
      phone: "+31-6-12345678"
    address:
      street: "Keizersgracht 123"
      city: "Amsterdam"
      country: "NL"
      postalCode: "1015 CJ"

expected_output:
  id: "usr-42"
  name: "Bob Jensen"
  firstName: "Bob"
  lastName: "Jensen"
  email: "bjensen@example.com"
  phone: "+31-6-12345678"
  city: "Amsterdam"
  country: "NL"
```
---


### S-001-12: Nest Flat Object

The reverse of S-001-11: restructure a flat object into a nested hierarchy.


---

```yaml
scenario: S-001-12
name: nest-flat-object
description: >
  Restructure a flat API response into a nested hierarchy for a richer
  frontend model. Reverse of S-001-11.
tags: [structural, nesting, reverse]
refs: [FR-001-01]

transform:
  lang: jslt
  expr: |
    {
      "id": .id,
      "profile": {
        "name": {
          "first": .firstName,
          "last": .lastName,
          "full": .firstName + " " + .lastName
        },
        "contact": {
          "email": .email,
          "phone": .phone
        }
      }
    }

input:
  id: "usr-42"
  firstName: "Bob"
  lastName: "Jensen"
  email: "bjensen@example.com"
  phone: "+31-6-12345678"

expected_output:
  id: "usr-42"
  profile:
    name:
      first: "Bob"
      last: "Jensen"
      full: "Bob Jensen"
    contact:
      email: "bjensen@example.com"
      phone: "+31-6-12345678"
```
---


### S-001-13: Array-of-Objects Reshaping

Transform an array of objects from one shape into another shape.


---

```yaml
scenario: S-001-13
name: reshape-array-of-objects
description: >
  Transform SCIM-style user list response into a simplified list API
  format. Tests array iteration with [for ...].
tags: [structural, array, scim]
refs: [FR-001-01]

transform:
  lang: jslt
  expr: |
    {
      "total": .totalResults,
      "users": [for (.Resources)
        {
          "id": .id,
          "username": .userName,
          "displayName": .displayName,
          "email": .emails[0].value,
          "active": .active
        }
      ]
    }

input:
  totalResults: 2
  startIndex: 1
  itemsPerPage: 10
  schemas: ["urn:ietf:params:scim:api:messages:2.0:ListResponse"]
  Resources:
    - id: "2819c223-7f76-453a-919d-413861904646"
      schemas: ["urn:ietf:params:scim:schemas:core:2.0:User"]
      userName: "bjensen"
      displayName: "Bob Jensen"
      emails:
        - value: "bjensen@example.com"
          type: "work"
          primary: true
      active: true
    - id: "c75ad752-64ae-4823-840d-ffa80929976c"
      schemas: ["urn:ietf:params:scim:schemas:core:2.0:User"]
      userName: "jsmith"
      displayName: "Jane Smith"
      emails:
        - value: "jsmith@example.com"
          type: "work"
          primary: true
        - value: "jane@personal.com"
          type: "home"
      active: false

expected_output:
  total: 2
  users:
    - id: "2819c223-7f76-453a-919d-413861904646"
      username: "bjensen"
      displayName: "Bob Jensen"
      email: "bjensen@example.com"
      active: true
    - id: "c75ad752-64ae-4823-840d-ffa80929976c"
      username: "jsmith"
      displayName: "Jane Smith"
      email: "jsmith@example.com"
      active: false
```
---


### S-001-14: OAuth Token Response Normalization

Normalize different OAuth token responses into a consistent format.


---

```yaml
scenario: S-001-14
name: oauth-token-normalization
description: >
  Normalize an OAuth2 token response from a specific IdP into a standard
  format. Tests conditional logic and default values.
tags: [structural, oauth, conditional, normalization]
refs: [FR-001-01]

transform:
  lang: jslt
  expr: |
    {
      "accessToken": .access_token,
      "tokenType": if (.token_type) .token_type else "Bearer",
      "expiresIn": .expires_in,
      "refreshToken": .refresh_token,
      "scope": if (is-string(.scope)) split(.scope, " ") else .scope,
      "idToken": .id_token
    }

input:
  access_token: "eyJhbGciOiJSUzI1NiIs..."
  token_type: "Bearer"
  expires_in: 3600
  refresh_token: "dGhpcyBpcyBhIHJlZnJlc2g..."
  scope: "openid profile email"
  id_token: "eyJhbGciOiJSUzI1NiIs..."

expected_output:
  accessToken: "eyJhbGciOiJSUzI1NiIs..."
  tokenType: "Bearer"
  expiresIn: 3600
  refreshToken: "dGhpcyBpcyBhIHJlZnJlc2g..."
  scope: ["openid", "profile", "email"]
  idToken: "eyJhbGciOiJSUzI1NiIs..."
```

---

## Category 4: Conditional Logic

Transformations that produce different output structures based on input values.


---

### S-001-15: Conditional Output Shape — Error vs Success

Produce different output structures based on whether the response is an error.


---

```yaml
scenario: S-001-15
name: conditional-error-vs-success
description: >
  Produce a different output shape depending on whether the upstream response
  indicates success or error. Tests if/else conditional logic.
tags: [conditional, error-handling, structural]
refs: [FR-001-01]

transform:
  lang: jslt
  expr: |
    if (.error)
      {
        "success": false,
        "error": {
          "code": .error,
          "message": .error_description,
          "details": .detail
        }
      }
    else
      {
        "success": true,
        "data": {
          "id": .id,
          "name": .name,
          "status": .status
        }
      }

input:
  error: "invalid_grant"
  error_description: "The provided grant is invalid or expired"
  detail: "Grant type 'authorization_code' requires a valid code"

expected_output:
  success: false
  error:
    code: "invalid_grant"
    message: "The provided grant is invalid or expired"
    details: "Grant type 'authorization_code' requires a valid code"
```
---


### S-001-16: Conditional Output Shape — Success Path

Same transform as S-001-15 but with a success response.


---

```yaml
scenario: S-001-16
name: conditional-success-path
description: >
  Same transform as S-001-15 but with a success response to verify the
  other branch of the conditional.
tags: [conditional, structural]
refs: [FR-001-01]

transform:
  # Same transform as S-001-15
  lang: jslt
  expr: |
    if (.error)
      {
        "success": false,
        "error": {
          "code": .error,
          "message": .error_description,
          "details": .detail
        }
      }
    else
      {
        "success": true,
        "data": {
          "id": .id,
          "name": .name,
          "status": .status
        }
      }

input:
  id: "usr-42"
  name: "Bob Jensen"
  status: "active"

expected_output:
  success: true
  data:
    id: "usr-42"
    name: "Bob Jensen"
    status: "active"
```
---


### S-001-17: Value Mapping (Enum Translation)

Map backend enum values to frontend-friendly labels.


---

```yaml
scenario: S-001-17
name: value-mapping-enum-translation
description: >
  Map backend status codes to frontend-friendly labels using conditional
  expressions. Common integration pattern.
tags: [conditional, value-mapping, enum]
refs: [FR-001-01]

transform:
  lang: jslt
  expr: |
    {
      "orderId": .order_id,
      "status":
        if   (.status == "PEND") "Pending"
        else if (.status == "PROC") "Processing"
        else if (.status == "SHIP") "Shipped"
        else if (.status == "DLVD") "Delivered"
        else if (.status == "CNCL") "Cancelled"
        else .status,
      "priority":
        if   (.priority_level >= 9) "critical"
        else if (.priority_level >= 7) "high"
        else if (.priority_level >= 4) "medium"
        else "low"
    }

input:
  order_id: "ORD-2026-0001"
  status: "SHIP"
  priority_level: 8

expected_output:
  orderId: "ORD-2026-0001"
  status: "Shipped"
  priority: "high"
```

---

## Category 5: Edge Cases & Error Handling
---


### S-001-18: Passthrough — No Matching Transform

When no transform profile matches, the message passes through unchanged.


---

```yaml
scenario: S-001-18
name: passthrough-no-match
description: >
  When the input does not match any transform profile, it MUST be passed
  through completely unmodified.
tags: [passthrough, edge-case, safety]
refs: [FR-001-06]

transform: null   # No transform matches

input:
  whatever: "this is"
  completely: "arbitrary"
  nested:
    data: [1, 2, 3]

expected_output:
  whatever: "this is"
  completely: "arbitrary"
  nested:
    data: [1, 2, 3]
```
---


### S-001-19: Passthrough — Invalid JSON Body

Non-JSON body should pass through without transformation.


---

```yaml
scenario: S-001-19
name: passthrough-invalid-json
description: >
  When the message body is not valid JSON, the engine MUST pass through
  unmodified. No transformation, no error.
tags: [passthrough, edge-case, safety]
refs: [FR-001-06, FR-001-04]

transform:
  lang: jslt
  expr: |
    { "this": "should never execute" }

input_raw: "<html><body>Not JSON</body></html>"

expected_output_raw: "<html><body>Not JSON</body></html>"
expected_transform_applied: false
```
---


### S-001-20: Open-World — Extra Fields Preserved

Fields not mentioned in the transform pass through unchanged.


---

```yaml
scenario: S-001-20
name: open-world-extra-fields
description: >
  When the input contains fields not mentioned in the transform spec,
  those fields MUST be preserved in the output (open-world assumption).
  Tests JSLT's * : . object matching.
tags: [open-world, passthrough, forward-compatibility]
refs: [FR-001-01, NFR-001-04]

transform:
  lang: jslt
  expr: |
    {
      "userId": .id,
      "name": .name,
      * : .
    }

input:
  id: "usr-42"
  name: "Bob Jensen"
  email: "bjensen@example.com"
  custom_field: "should survive"
  _new_field_from_v2: true

expected_output:
  userId: "usr-42"
  name: "Bob Jensen"
  id: "usr-42"  # * : . also passes through 'id'
  email: "bjensen@example.com"
  custom_field: "should survive"
  _new_field_from_v2: true
```
---


### S-001-21: Empty Input Object

Transform handles empty input gracefully.


---

```yaml
scenario: S-001-21
name: empty-input-object
description: >
  Transform handles empty JSON object input without error.
  All derived fields should be null or use defaults.
tags: [edge-case, empty, robustness]
refs: [FR-001-01]

transform:
  lang: jslt
  expr: |
    {
      "id": .id,
      "name": if (.name) .name else "Anonymous",
      "status": if (.status) .status else "unknown"
    }

input: {}

expected_output:
  # Note: JSLT omits keys whose value is null — no `id` key here
  name: "Anonymous"
  status: "unknown"
```
---


### S-001-22: Null Values in Input

Transform correctly handles null values in input fields.


---

```yaml
scenario: S-001-22
name: null-values-in-input
description: >
  Transform correctly handles null values — they should be passed through
  as null, not dropped or converted to empty strings.
tags: [edge-case, null, robustness]
refs: [FR-001-01]

transform:
  lang: jslt
  expr: |
    {
      "id": .id,
      "name": .name,
      "email": .email,
      "hasEmail": .email != null
    }

input:
  id: "usr-42"
  name: "Bob Jensen"
  email: null

expected_output:
  id: "usr-42"
  name: "Bob Jensen"
  # Note: JSLT omits keys whose value is null — no `email` key here
  hasEmail: false
```
---


### S-001-23: Large Array Transformation

Performance scenario: transform a large array of 100 objects.


---

```yaml
scenario: S-001-23
name: large-array-performance
description: >
  Transform a large array of objects. Tests that the engine handles
  non-trivial array sizes within the latency budget (<5ms).
tags: [performance, array, large]
refs: [FR-001-01, NFR-001-03]
performance_budget_ms: 5

transform:
  lang: jslt
  expr: |
    {
      "count": size(.items),
      "entries": [for (.items) {
        "id": .id,
        "label": .name,
        "active": .status == "ACTIVE"
      }]
    }

input:
  items:
    # 100 objects (represented as pattern — test harness generates full array)
    - { id: "item-001", name: "Item 1", status: "ACTIVE", _internal: "x" }
    - { id: "item-002", name: "Item 2", status: "INACTIVE", _internal: "y" }
    # ... (100 items total)

expected_output:
  count: 100
  entries:
    - { id: "item-001", label: "Item 1", active: true }
    - { id: "item-002", label: "Item 2", active: false }
    # ... (100 entries, no _internal field)
```
---


### S-001-24: JSLT Evaluation Error → Error Response

When a JSLT expression fails, the engine returns a configurable error response
to the caller (ADR-0022). The original message is NOT passed through.


---

```yaml
scenario: S-001-24
name: evaluation-error-returns-error-response
description: >
  When a JSLT expression references a function that doesn't exist, the engine
  MUST abort and return a configurable error response (ADR-0022). The original
  message is NOT passed through — the downstream service expects the transformed
  schema and would fail anyway.
tags: [error, error-response, safety, adr-0022]
refs: [FR-001-07]

transform:
  lang: jslt
  expr: |
    {
      "result": nonexistent-function(.value)
    }

input:
  value: "test"

expected_error_response:
  type: "urn:message-xform:error:transform-failed"
  title: "Transform Failed"
  status: 502
  detail_contains: "nonexistent-function"
  # Original message is NOT passed through
expected_passthrough: false
```

---


### S-001-86: Unknown Key in Spec → SpecParseException

Strict unknown-key detection in spec parsing. Unrecognized keys at any level
of the YAML spec are rejected immediately with a diagnostic message.


---

```yaml
scenario: S-001-86
name: unknown-key-in-spec-rejected
description: >
  When a spec YAML contains an unrecognized key (e.g. 'headers.request.add'
  instead of 'headers.add'), the SpecParser MUST throw a SpecParseException
  listing the unknown key(s) and the recognized keys for that block.
  This is the fail-fast validation rule added to FR-001-01.
tags: [error, spec-parse, strict-validation, fail-fast]
refs: [FR-001-01]

# No transform — this tests spec loading, not evaluation.
# The spec file itself is the "input".

spec_yaml: |
  id: unknown-header-key
  version: "1.0.0"
  description: Spec with unknown key in headers block
  headers:
    request:
      add:
        X-Custom: "value"

expected_error:
  type: SpecParseException
  message_contains:
    - "Unknown key"
    - "'headers'"
    - "request"
  spec_id: "unknown-header-key"
```

---

```yaml
scenario: S-001-86b
name: unknown-root-key-in-spec-rejected
description: >
  Unknown top-level key in spec YAML (e.g. 'routing') is rejected.
tags: [error, spec-parse, strict-validation, fail-fast]
refs: [FR-001-01]

spec_yaml: |
  id: unknown-root-key
  version: "1.0.0"
  description: Spec with unknown root-level key
  routing:
    paths: ["/api/**"]
  transform:
    lang: jslt
    expr: |
      .

expected_error:
  type: SpecParseException
  message_contains:
    - "Unknown key"
    - "'spec root'"
    - "routing"
  spec_id: "unknown-root-key"
```

---

## Category 6: Multi-Engine

Verify that the same logical transformation works across different engines
when they can express the same mapping.


---

### S-001-25: Multi-Engine — Simple Rename (JSLT)


---

```yaml
scenario: S-001-25
name: multi-engine-rename-jslt
description: >
  Simple rename transform using JSLT engine.
  Part of multi-engine comparison set with S-001-26, S-001-27.
tags: [multi-engine, jslt, rename]
refs: [FR-001-02]

transform:
  lang: jslt
  expr: |
    {
      "userName": .user_name,
      "emailAddress": .email
    }

input:
  user_name: "bjensen"
  email: "bjensen@example.com"

expected_output:
  userName: "bjensen"
  emailAddress: "bjensen@example.com"
```
---


### S-001-26: Multi-Engine — Simple Rename (JOLT)


---

```yaml
scenario: S-001-26
name: multi-engine-rename-jolt
description: >
  Same rename transform as S-001-25 but using JOLT engine.
  Validates engine SPI interchangeability.
tags: [multi-engine, jolt, rename]
refs: [FR-001-02]

transform:
  lang: jolt
  expr: |
    [{
      "operation": "shift",
      "spec": {
        "user_name": "userName",
        "email": "emailAddress"
      }
    }]

input:
  user_name: "bjensen"
  email: "bjensen@example.com"

expected_output:
  userName: "bjensen"
  emailAddress: "bjensen@example.com"
```
---


### S-001-27: Multi-Engine — Simple Rename (jq)


---

```yaml
scenario: S-001-27
name: multi-engine-rename-jq
description: >
  Same rename transform as S-001-25 but using jq engine.
  Validates engine SPI interchangeability.
tags: [multi-engine, jq, rename]
refs: [FR-001-02]

transform:
  lang: jq
  expr: |
    { userName: .user_name, emailAddress: .email }

input:
  user_name: "bjensen"
  email: "bjensen@example.com"

expected_output:
  userName: "bjensen"
  emailAddress: "bjensen@example.com"
```
---


### S-001-28: Unknown Engine — Rejected at Load Time


---

```yaml
scenario: S-001-28
name: unknown-engine-rejected
description: >
  When a spec declares an unknown engine, it MUST be rejected at load
  time with a clear error message.
tags: [multi-engine, error, validation]
refs: [FR-001-02]

transform:
  lang: nonexistent-engine
  expr: |
    whatever

expected_error:
  type: "unknown_engine"
  engine: "nonexistent-engine"
  message_contains: "Unknown expression engine: nonexistent-engine"
```

---

## Category 7: Bidirectional Round-Trip
---


### S-001-29: Bidirectional Round-Trip — PingAM Callbacks

Verify that forward → reverse produces a result that, when forwarded again,
produces the same output as the first forward pass.


---

```yaml
scenario: S-001-29
name: bidirectional-roundtrip-pingam
description: >
  Full bidirectional round-trip for PingAM callbacks:
  1. Forward: PingAM → Clean JSON
  2. Reverse: Clean JSON (with filled values) → PingAM
  3. Forward again: PingAM → Clean JSON (values preserved)
  Tests referential integrity of the transform pair.
tags: [bidirectional, round-trip, pingam, integration]
refs: [FR-001-03]

forward:
  lang: jslt
  expr: |
    {
      "authId": .authId,
      "stage": .stage,
      "fields": [for (.callbacks)
        {
          "label": .output[0].value,
          "fieldId": .input[0].name,
          "value": .input[0].value,
          "type": if (.type == "PasswordCallback") "password" else "text"
        }
      ]
    }

reverse:
  lang: jslt
  expr: |
    {
      "authId": .authId,
      "stage": .stage,
      "callbacks": [for (.fields)
        {
          "type": if (.type == "password") "PasswordCallback" else "NameCallback",
          "output": [{ "name": "prompt", "value": .label }],
          "input": [{ "name": .fieldId, "value": .value }]
        }
      ]
    }

# Step 1: PingAM response arrives
step_1_input:
  authId: "jwt-token-123"
  stage: "DataStore1"
  callbacks:
    - type: "NameCallback"
      output: [{ name: "prompt", value: "Username:" }]
      input: [{ name: "IDToken1", value: "" }]
    - type: "PasswordCallback"
      output: [{ name: "prompt", value: "Password:" }]
      input: [{ name: "IDToken2", value: "" }]

# Step 1 expected: forwarded to frontend
step_1_expected:
  authId: "jwt-token-123"
  stage: "DataStore1"
  fields:
    - label: "Username:"
      fieldId: "IDToken1"
      value: ""
      type: "text"
    - label: "Password:"
      fieldId: "IDToken2"
      value: ""
      type: "password"

# Step 2: Frontend fills values and submits
step_2_input:
  authId: "jwt-token-123"
  stage: "DataStore1"
  fields:
    - label: "Username:"
      fieldId: "IDToken1"
      value: "bjensen"
      type: "text"
    - label: "Password:"
      fieldId: "IDToken2"
      value: "Ch4ng31t"
      type: "password"

# Step 2 expected: reversed back to PingAM format
step_2_expected:
  authId: "jwt-token-123"
  stage: "DataStore1"
  callbacks:
    - type: "NameCallback"
      output: [{ name: "prompt", value: "Username:" }]
      input: [{ name: "IDToken1", value: "bjensen" }]
    - type: "PasswordCallback"
      output: [{ name: "prompt", value: "Password:" }]
      input: [{ name: "IDToken2", value: "Ch4ng31t" }]
```
---


### S-001-30: Bidirectional — Flat ↔ Nested


---

```yaml
scenario: S-001-30
name: bidirectional-flat-nested
description: >
  Bidirectional transform between flat API format and nested domain model.
  Forward: flat → nested. Reverse: nested → flat.
tags: [bidirectional, structural, round-trip]
refs: [FR-001-03]

forward:
  lang: jslt
  expr: |
    {
      "user": {
        "name": { "first": .firstName, "last": .lastName },
        "contact": { "email": .email }
      }
    }

reverse:
  lang: jslt
  expr: |
    {
      "firstName": .user.name.first,
      "lastName": .user.name.last,
      "email": .user.contact.email
    }

step_1_input:
  firstName: "Bob"
  lastName: "Jensen"
  email: "bjensen@example.com"

step_1_expected:
  user:
    name:
      first: "Bob"
      last: "Jensen"
    contact:
      email: "bjensen@example.com"

step_2_input:
  user:
    name:
      first: "Bob"
      last: "Jensen"
    contact:
      email: "bjensen@example.com"

step_2_expected:
  firstName: "Bob"
  lastName: "Jensen"
  email: "bjensen@example.com"
```

---

## Category 8: Error Response Normalization
---


### S-001-31: RFC 9457 Problem Details Normalization


---

```yaml
scenario: S-001-31
name: error-normalize-rfc9457
description: >
  Transform a backend-specific error response into RFC 9457 Problem Details
  format. Common gateway use case for error format standardization.
tags: [error-normalization, rfc9457, structural]
refs: [FR-001-07]

transform:
  lang: jslt
  expr: |
    {
      "type": "https://api.example.com/errors/" + (if (.code) .code else "unknown"),
      "title": if (.message) .message else "An error occurred",
      "status": if (.httpStatus) .httpStatus else 500,
      "detail": .description,
      "instance": .requestId
    }

input:
  code: "INVALID_INPUT"
  message: "Validation failed"
  httpStatus: 400
  description: "Field 'email' must be a valid email address"
  requestId: "/requests/req-abc-123"
  stack_trace: "com.example.ValidationException at..."
  internal_error_code: 42

expected_output:
  type: "https://api.example.com/errors/INVALID_INPUT"
  title: "Validation failed"
  status: 400
  detail: "Field 'email' must be a valid email address"
  instance: "/requests/req-abc-123"
```
---


### S-001-32: PingAM Error Normalization


---

```yaml
scenario: S-001-32
name: pingam-error-normalization
description: >
  PingAM returns errors in a non-standard format. Normalize to a consistent
  error envelope for the frontend.
tags: [pingam, error-normalization, structural]
refs: [FR-001-07]

transform:
  lang: jslt
  expr: |
    {
      "success": false,
      "error": {
        "code": if (.code) .code else 401,
        "reason": .reason,
        "message": .message
      }
    }

input:
  code: 401
  reason: "Unauthorized"
  message: "Authentication Failed!!"
  detail:
    errorCode: "301"

expected_output:
  success: false
  error:
    code: 401
    reason: "Unauthorized"
    message: "Authentication Failed!!"
```

---

## Category 9: Header ↔ Body Transforms

Scenarios validating header-to-body and body-to-header injection (ADR-0002, FR-001-10).


---

### S-001-33: Header-to-Body Injection via `$headers`

Inject a request header value into the transformed response body.


---

```yaml
scenario: S-001-33
name: header-to-body-injection
description: >
  Inject the X-Request-ID header value into the response body using the
  $headers read-only JSLT variable. Validates ADR-0002 header-to-body bridge.
tags: [header, body, injection, adr-0002]
refs: [FR-001-10]

transform:
  lang: jslt
  expr: |
    {
      "requestId": $headers."X-Request-ID",
      "correlationId": $headers."X-Correlation-ID",
      "data": .data,
      "status": .status
    }

request_headers:
  X-Request-ID: "req-abc-123"
  X-Correlation-ID: "corr-xyz-789"
  Content-Type: "application/json"

input:
  data: "some payload"
  status: "ok"

expected_output:
  requestId: "req-abc-123"
  correlationId: "corr-xyz-789"
  data: "some payload"
  status: "ok"
```
---


### S-001-34: Body-to-Header Injection via Dynamic `expr`

Extract values from the transformed body and emit them as response headers.


---

```yaml
scenario: S-001-34
name: body-to-header-injection
description: >
  Extract error code and auth method from the transformed response body and
  emit them as response headers using dynamic expr in the headers.add block.
  Validates ADR-0002 body-to-header bridge.
tags: [header, body, injection, dynamic, adr-0002]
refs: [FR-001-10]

transform:
  lang: jslt
  expr: |
    {
      "type": if (.callbacks) "challenge" else "simple",
      "authId": .authId,
      "error": .error
    }

headers:
  add:
    X-Auth-Method:
      expr: .type
    X-Error-Code:
      expr: .error.code
    X-Transformed-By: "message-xform"
  remove: ["X-Internal-*"]

input:
  authId: "eyJ0eXAi..."
  callbacks:
    - type: "NameCallback"
  error:
    code: "AUTH_REQUIRED"
    message: "Authentication required"

expected_output:
  type: "challenge"
  authId: "eyJ0eXAi..."
  error:
    code: "AUTH_REQUIRED"
    message: "Authentication required"

expected_headers:
  X-Auth-Method: "challenge"
  X-Error-Code: "AUTH_REQUIRED"
  X-Transformed-By: "message-xform"
```
---


### S-001-35: Missing Header — `$headers` Returns Null

Edge case: referencing a header that doesn't exist returns null.


---

```yaml
scenario: S-001-35
name: missing-header-returns-null
description: >
  When $headers references a header that was not sent, JSLT returns null.
  The transform should handle this gracefully.
tags: [header, edge-case, null, adr-0002]
refs: [FR-001-10]

transform:
  lang: jslt
  expr: |
    {
      "requestId": $headers."X-Request-ID",
      "data": .data
    }

request_headers:
  Content-Type: "application/json"
  # Note: X-Request-ID is NOT present

input:
  data: "payload"

expected_output:
  requestId: null
  data: "payload"
```

---

## Category 10: Status Code Transforms

Scenarios validating status code transformation (ADR-0003, FR-001-11).


---

### S-001-36: Conditional Status Change — Error Body → 400

The key use case: upstream returns 200 but body contains an error → change to 400.


---

```yaml
scenario: S-001-36
name: conditional-status-error-body
description: >
  Upstream returns 200 with an error in the body. The status block changes
  the response to 400 when the error field is present.
  Validates ADR-0003 conditional when predicate.
tags: [status, conditional, error, adr-0003]
refs: [FR-001-11]

transform:
  lang: jslt
  expr: |
    {
      "success": false,
      "error": .error,
      "message": .error_description
    }

status:
  set: 400
  when: '.error != null'

input:
  error: "invalid_grant"
  error_description: "The provided grant is invalid or expired"

request_status: 200

expected_output:
  success: false
  error: "invalid_grant"
  message: "The provided grant is invalid or expired"

expected_status: 400
```
---


### S-001-37: `$status` in Body Expression

Use the original status code inside the JSLT body expression.


---

```yaml
scenario: S-001-37
name: status-in-body-expression
description: >
  Use $status to include the original HTTP status code in the transformed
  body. Validates ADR-0003 $status read-only variable.
tags: [status, body, variable, adr-0003]
refs: [FR-001-11]

transform:
  lang: jslt
  expr: |
    {
      "success": $status < 400,
      "httpStatus": $status,
      "data": .data
    }

input:
  data: "payload"

request_status: 200

expected_output:
  success: true
  httpStatus: 200
  data: "payload"

expected_status: 200
```
---


### S-001-38: Unconditional Status Set

Set status code unconditionally (no `when` predicate).


---

```yaml
scenario: S-001-38
name: unconditional-status-set
description: >
  Set the response status code to 202 unconditionally, regardless of body.
  Validates ADR-0003 unconditional status set.
tags: [status, unconditional, adr-0003]
refs: [FR-001-11]

transform:
  lang: jslt
  expr: |
    {
      "accepted": true,
      "id": .id
    }

status:
  set: 202

input:
  id: "job-42"

request_status: 200

expected_output:
  accepted: true
  id: "job-42"

expected_status: 202
```

---

### S-001-38i: Status When Predicate Evaluation Error — Keep Original Status

**Narrative**: When the `when` predicate on a status block fails at evaluation time
(e.g., type error, missing function argument), the engine keeps the original status
code and does NOT abort the entire transform — the body transform still succeeds.


---

```yaml
scenario: S-001-38i
title: Status when predicate evaluation error — keep original status
category: Status Code Transforms
refs: [FR-001-11]
references: [ADR-0003]
tags: [status, error-handling, when-predicate]

transform:
  lang: jslt
  expr: |
    { "data": .data }

status:
  set: 500
  when: 'contains(.nonexistentArray, "value")'

input:
  data: "test"

request_status: 200

expected_output:
  data: "test"

expected_status: 200

notes: >
  The when predicate calls contains() on a null/missing field, which triggers
  a JSLT evaluation error. Per ADR-0003, status predicate errors are non-fatal:
  the engine logs a warning and preserves the original status code. The body
  transform is unaffected.
```

---

## Category 11: URL Rewriting

Scenarios validating URL rewriting — path rewrite, query parameter operations,
and HTTP method override (ADR-0027, FR-001-12).


---

### S-001-38a: URL Path Rewrite — De-polymorphize Dispatch Endpoint

The primary use case: extract routing fields from the request body and construct
a specific REST-style URL.


---

```yaml
scenario: S-001-38a
name: url-path-rewrite-dispatch
description: >
  A polymorphic POST /dispatch endpoint is de-polymorphized by extracting
  .action and .resourceId from the body to construct a REST URL like
  /api/users/123. The body transform strips the routing fields. URL
  path.expr evaluates against the ORIGINAL (pre-transform) body, so routing
  fields are available even though the body transform erases them.
  Validates ADR-0027 original-body evaluation context.
tags: [url, path-rewrite, de-polymorphize, adr-0027]
refs: [FR-001-12]

direction: request

transform:
  lang: jslt
  expr: |
    {* - action, - resourceId : .}

url:
  path:
    expr: '"/api/" + .action + "/" + .resourceId'

input:
  action: "users"
  resourceId: "123"
  name: "Bob Jensen"
  email: "bjensen@example.com"

request_path: "/dispatch"

expected_output:
  name: "Bob Jensen"
  email: "bjensen@example.com"

expected_path: "/api/users/123"
```
---


### S-001-38b: URL Query Parameter Add/Remove


---

```yaml
scenario: S-001-38b
name: url-query-param-operations
description: >
  Add static and dynamic query parameters. Remove matching parameters by
  glob pattern. Dynamic query expr evaluates against the original body.
  Validates FR-001-12 url.query operations.
tags: [url, query, add, remove, glob, adr-0027]
refs: [FR-001-12]

direction: request

transform:
  lang: jslt
  expr: .

url:
  query:
    add:
      format: "json"
      tenant:
        expr: .tenantId
    remove: ["_debug", "_internal"]

request_headers:
  X-Correlation-ID: "corr-abc-123"

input:
  tenantId: "acme-corp"
  data: "payload"

request_path: "/api/resource"
request_query: "_debug=true&_internal=metric&existing=keep"

expected_output:
  tenantId: "acme-corp"
  data: "payload"

expected_path: "/api/resource"
expected_query_params:
  format: "json"
  tenant: "acme-corp"
  existing: "keep"
  # _debug and _internal removed by glob
```
---


### S-001-38c: HTTP Method Override with Conditional Predicate


---

```yaml
scenario: S-001-38c
name: url-method-override-conditional
description: >
  Override the HTTP method based on a body field. POST /dispatch with
  action "delete" becomes DELETE /api/users/123. Validates ADR-0027
  method override with when predicate against original body.
tags: [url, method, override, conditional, adr-0027]
refs: [FR-001-12]

direction: request

transform:
  lang: jslt
  expr: |
    {* - action, - resourceId : .}

url:
  path:
    expr: '"/api/users/" + .resourceId'
  method:
    set: "DELETE"
    when: '.action == "delete"'

input:
  action: "delete"
  resourceId: "456"

request_path: "/dispatch"
request_method: "POST"

expected_output: {}

expected_path: "/api/users/456"
expected_method: "DELETE"
```
---


### S-001-38d: URL Path Expr Returns Null — Error


---

```yaml
scenario: S-001-38d
name: url-path-expr-returns-null
description: >
  When url.path.expr evaluates to null (e.g., missing body field), the
  engine throws ExpressionEvalException. URL rewrite must produce a
  valid string.
tags: [url, error, null, validation]
refs: [FR-001-12]

direction: request

transform:
  lang: jslt
  expr: .

url:
  path:
    expr: .missingField

input:
  data: "payload"

request_path: "/original"

expected_error:
  type: "expression-eval"
  message: "url.path.expr must return a string, got null"
```
---


### S-001-38e: Invalid HTTP Method — Rejected at Load Time


---

```yaml
scenario: S-001-38e
name: url-method-invalid-rejected
description: >
  A spec declares url.method.set with an invalid HTTP method. The engine
  must reject this at load time with SpecParseException.
tags: [url, method, validation, load-time]
refs: [FR-001-12]

url:
  method:
    set: "YOLO"

transform:
  lang: jslt
  expr: .

expected_error:
  type: "spec-parse"
  message: "invalid HTTP method 'YOLO' — must be one of: GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS"
```
---


### S-001-38f: URL Block on Response Transform — Ignored with Warning


---

```yaml
scenario: S-001-38f
name: url-block-on-response-ignored
description: >
  A response-direction transform includes a url block. Since URL rewriting
  is only meaningful for request transforms, the url block is ignored and
  a warning is logged at load time.
tags: [url, direction, response, warning]
refs: [FR-001-12]

direction: response

transform:
  lang: jslt
  expr: |
    { "data": .data }

url:
  path:
    expr: '"/should-be-ignored"'

input:
  data: "payload"

request_path: "/original"
request_status: 200

expected_output:
  data: "payload"

expected_path: "/original"    # unchanged — url block ignored
expected_warning: "url block is ignored for response-direction transforms"
```
---


### S-001-38g: URL-to-Body Extraction — Path Segment and Query Param into Nested Body

Extract fields from the URL (path segment + query parameter) and inject them into
nested body fields. The URL is then cleaned (path simplified, query param removed).
This is the reverse of de-polymorphization: instead of body → URL, it's URL → body.


---

```yaml
scenario: S-001-38g
name: url-to-body-extraction
description: >
  A multi-tenant API encodes the tenant in the URL path and a tracing ID in a
  query parameter. The transform extracts both into nested body fields and cleans
  the URL so the downstream service receives a tenant-agnostic path. Body
  transform uses $requestPath and $queryParams context variables. URL rewrite
  constructs a simplified path and removes the extracted query param.
  Validates FR-001-12 + ADR-0021 ($queryParams) working together.
tags: [url, body, extraction, path-segment, query-param, adr-0027, adr-0021]
refs: [FR-001-12]

direction: request

transform:
  lang: jslt
  expr: |
    let parts = split($requestPath, "/")
    . + {
      "routing": {
        "tenant": $parts[2],
        "traceId": $queryParams.trace
      }
    }

url:
  path:
    expr: |
      let parts = split($requestPath, "/")
      "/api/" + $parts[3]
  query:
    remove: ["trace"]

input:
  name: "Bob Jensen"
  email: "bjensen@example.com"

request_path: "/api/acme-corp/users"
request_query: "trace=tid-abc-123&format=json"

expected_output:
  name: "Bob Jensen"
  email: "bjensen@example.com"
  routing:
    tenant: "acme-corp"
    traceId: "tid-abc-123"

expected_path: "/api/users"
expected_query_params:
  format: "json"
  # trace removed by url.query.remove
```

---

## Category 12: Engine Capability Validation

Scenarios validating engine support matrix enforcement (ADR-0004, FR-001-02).


---

### S-001-39: JOLT Engine with Unsupported Predicate — Rejected at Load Time

A spec using `lang: jolt` with a `when` predicate must be rejected.


---

```yaml
scenario: S-001-39
name: jolt-unsupported-predicate-rejected
description: >
  A spec declares lang: jolt with a when predicate. Since JOLT does not
  support predicates (per engine support matrix), the engine must reject
  the spec at load time with a clear diagnostic.
  Validates ADR-0004 load-time capability validation.
tags: [engine, capability, jolt, validation, adr-0004]
refs: [FR-001-02]

transform:
  lang: jolt
  expr: |
    [{"operation": "shift", "spec": {"id": "userId"}}]

status:
  set: 400
  when: '.error != null'

input:
  id: "usr-42"

expected_error:
  type: "capability-violation"
  message: "engine 'jolt' does not support predicates — use 'jslt' or 'jq'"
```
---


### S-001-40: JOLT Engine with `$headers` Reference — Rejected at Load Time


---

```yaml
scenario: S-001-40
name: jolt-unsupported-headers-rejected
description: >
  A spec declares lang: jolt and references $headers. Since JOLT does not
  support context variables, the engine must reject at load time.
  Validates ADR-0004 load-time capability validation.
tags: [engine, capability, jolt, validation, adr-0004]
refs: [FR-001-02]

transform:
  lang: jolt
  expr: |
    [{"operation": "shift", "spec": {"id": "userId"}}]

headers:
  add:
    X-Error-Code:
      expr: .error.code

input:
  id: "usr-42"

expected_error:
  type: "capability-violation"
  message: "engine 'jolt' does not support context variables ($headers/$status)"
```

---

## Category 13: Version Pinning

Scenarios validating profile-to-spec version pinning (ADR-0005, FR-001-05).


---

### S-001-41: Concurrent Spec Versions — Different Routes Use Different Versions


---

```yaml
scenario: S-001-41
name: concurrent-spec-versions
description: >
  Two profiles reference different versions of the same spec. Both must
  resolve independently at load time. Validates ADR-0005 concurrent versioning.
tags: [version, profile, concurrent, adr-0005]
refs: [FR-001-05]

loaded_specs:
  - id: callback-prettify
    version: "1.0.0"
    transform:
      lang: jslt
      expr: '{ "v": 1, "data": .data }'
  - id: callback-prettify
    version: "2.0.0"
    transform:
      lang: jslt
      expr: '{ "v": 2, "payload": .data }'

profiles:
  - spec: callback-prettify@1.0.0
    match: { path: "/api/v1/*" }
  - spec: callback-prettify@2.0.0
    match: { path: "/api/v2/*" }

# Request to /api/v1/test
test_request:
  path: "/api/v1/test"
  input: { data: "hello" }
  expected_output: { v: 1, data: "hello" }

# Request to /api/v2/test
test_request_2:
  path: "/api/v2/test"
  input: { data: "hello" }
  expected_output: { v: 2, payload: "hello" }
```
---


### S-001-42: Missing Spec Version — Rejected at Load Time


---

```yaml
scenario: S-001-42
name: missing-spec-version-rejected
description: >
  A profile references callback-prettify@3.0.0 but only v1 and v2 are loaded.
  The engine must reject the profile at load time.
  Validates ADR-0005 fail-fast version resolution.
tags: [version, profile, validation, adr-0005]
refs: [FR-001-05]

loaded_specs:
  - id: callback-prettify
    version: "1.0.0"

profiles:
  - spec: callback-prettify@3.0.0
    match: { path: "/api/v3/*" }

expected_error:
  type: "spec-not-found"
  message: "spec 'callback-prettify@3.0.0' not found — available versions: 1.0.0"
```
---


### S-001-43: Bare Spec Reference — Resolves to Latest Version


---

```yaml
scenario: S-001-43
name: bare-spec-resolves-to-latest
description: >
  A profile references 'callback-prettify' without a version suffix.
  The engine must resolve to the latest loaded version (highest semver).
  Validates ADR-0005 latest-version fallback.
tags: [version, profile, latest, adr-0005]
refs: [FR-001-05]

loaded_specs:
  - id: callback-prettify
    version: "1.0.0"
    transform:
      lang: jslt
      expr: '{ "v": 1, "data": .data }'
  - id: callback-prettify
    version: "2.0.0"
    transform:
      lang: jslt
      expr: '{ "v": 2, "payload": .data }'

profiles:
  - spec: callback-prettify           # no @version
    match: { path: "/api/latest/*" }

test_request:
  path: "/api/latest/test"
  input: { data: "hello" }
  expected_output: { v: 2, payload: "hello" }   # resolves to v2 (latest)
```

---

## Category 14: Profile Match Resolution

Scenarios validating most-specific-wins profile matching (ADR-0006, FR-001-05, NFR-001-08).


---

### S-001-44: Specific Path Beats Wildcard


---

```yaml
scenario: S-001-44
name: specific-path-beats-wildcard
description: >
  Two profiles match the same request. The profile with more literal path
  segments wins. Validates ADR-0006 specificity scoring.
tags: [profile, match, specificity, adr-0006]
refs: [FR-001-05]

profiles:
  - id: catch-all
    spec: generic-transform@1.0.0
    match: { path: "/json/*" }              # 1 literal segment

  - id: auth-specific
    spec: auth-transform@1.0.0
    match: { path: "/json/*/authenticate" }  # 2 literal segments

test_request:
  path: "/json/alpha/authenticate"

expected_match:
  profile: auth-specific
  reason: "specificity score 2 > 1"
```
---


### S-001-45: Ambiguous Tie — Rejected at Load Time


---

```yaml
scenario: S-001-45
name: ambiguous-tie-rejected
description: >
  Two profiles have the same specificity score and same constraint count.
  The engine must reject both at load time as ambiguous.
  Validates ADR-0006 tie-breaking rules.
tags: [profile, match, ambiguous, validation, adr-0006]
refs: [FR-001-05]

profiles:
  - id: profile-a
    spec: transform-a@1.0.0
    match: { path: "/api/*/users" }         # 2 literal segments

  - id: profile-b
    spec: transform-b@1.0.0
    match: { path: "/api/v1/*" }            # 2 literal segments

expected_error:
  type: "ambiguous-match"
  message: "profiles 'profile-a' and 'profile-b' have identical specificity — resolve ambiguity"
```
---


### S-001-46: Constraint Count Tie-Breaking


---

```yaml
scenario: S-001-46
name: constraint-count-tiebreaker
description: >
  Two profiles have the same specificity score but different constraint counts.
  The profile with more constraints (method, content-type) wins.
  Validates ADR-0006 tie-breaking by constraint count.
tags: [profile, match, tiebreaker, adr-0006]
refs: [FR-001-05]

profiles:
  - id: broad
    spec: transform-a@1.0.0
    match:
      path: "/api/*/users"                  # 2 literal segments, 1 constraint

  - id: narrow
    spec: transform-b@1.0.0
    match:
      path: "/api/*/users"                  # 2 literal segments
      method: POST                           # +1 constraint
      content-type: "application/json"       # +1 constraint = 3 total

test_request:
  path: "/api/v1/users"
  method: POST
  content-type: "application/json"

expected_match:
  profile: narrow
  reason: "equal specificity, 3 constraints > 1 constraint"
```

---

## Category 15: Observability

Scenarios validating the telemetry SPI and trace correlation (ADR-0007, NFR-001-09, NFR-001-10).


---

### S-001-47: TelemetryListener Receives Transform Lifecycle Events


---

```yaml
scenario: S-001-47
name: telemetry-listener-lifecycle
description: >
  A registered TelemetryListener receives onTransformStarted,
  onTransformCompleted (or onTransformFailed) events for every transform
  evaluation. Events carry specId, specVersion, direction, and duration —
  but never body content or header values.
  Validates NFR-001-09 telemetry SPI.
tags: [observability, telemetry, spi, adr-0007]
refs: [NFR-001-09]

setup:
  telemetry_listener: mock          # test double captures events

transform:
  spec: callback-prettify@1.0.0
  direction: response
  input: { data: "hello" }

expected_telemetry_events:
  - type: TransformStarted
    specId: callback-prettify
    specVersion: "1.0.0"
    direction: response
  - type: TransformCompleted
    specId: callback-prettify
    specVersion: "1.0.0"
    direction: response
    duration_ms: ">= 0"
    # MUST NOT contain: body, header values, sensitive data
```
---


### S-001-48: Trace Context Propagation — X-Request-ID in Log Output


---

```yaml
scenario: S-001-48
name: trace-context-propagation
description: >
  The engine propagates incoming X-Request-ID through all structured
  log entries and telemetry events. No new traces are created.
  Validates NFR-001-10 trace correlation.
tags: [observability, tracing, correlation, adr-0007]
refs: [NFR-001-10]

request:
  headers:
    X-Request-ID: "abc-123-def"
    traceparent: "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
  body: { data: "hello" }

expected_log_entry:
  fields:
    request_id: "abc-123-def"
    trace_id: "4bf92f3577b34da6a3ce929d0e0e4736"
    profile_id: "*"                  # whatever matched
    spec_id: "*"
```

---

## Category 16: Profile-Level Chaining (ADR-0008)

Mixed-engine composition at the profile level — the sanctioned approach per ADR-0008.


---

### S-001-49: Profile-Level Chaining — JOLT Structural Shift → JSLT Conditional Enrichment


---

```yaml
scenario: S-001-49
name: profile-chain-jolt-then-jslt
description: >
  Validates that profile-level chaining enables mixed-engine composition:
  JOLT performs a structural shift (rename fields), then JSLT adds conditional
  logic and header-aware enrichment. Two separate specs, same route in profile.
  This is the sanctioned approach per ADR-0008 (no spec-level pipelines).
tags: [profile-chaining, mixed-engine, jolt, jslt, adr-0008]
refs: [FR-001-01, FR-001-05]

# Spec 1: JOLT structural shift (rename user_id → userId, etc.)
spec_1:
  id: rename-to-camelcase
  version: "1.0.0"
  transform:
    lang: jolt
    expr:
      - operation: shift
        spec:
          user_id: userId
          first_name: firstName
          last_name: lastName
          email_address: email
          is_active: active

# Spec 2: JSLT conditional enrichment (adds metadata using $headers)
spec_2:
  id: add-auth-metadata
  version: "1.0.0"
  transform:
    lang: jslt
    expr: |
      . + {
        "_auth": {
          "authenticatedBy": $headers."X-Auth-Method",
          "tier": if (.active) "premium" else "basic"
        }
      }

# Profile: chains spec_1 → spec_2 on the same route
profile:
  id: user-api-v2
  transforms:
    - spec: rename-to-camelcase@1.0.0
      direction: response
      match:
        path: "/api/v2/users/*"
        method: GET
    - spec: add-auth-metadata@1.0.0
      direction: response
      match:
        path: "/api/v2/users/*"
        method: GET

request:
  headers:
    X-Auth-Method: "OAuth2"
  body:
    user_id: "usr-42"
    first_name: "Bob"
    last_name: "Jensen"
    email_address: "bjensen@example.com"
    is_active: true

# After JOLT: fields renamed. After JSLT: metadata added.
expected_output:
  userId: "usr-42"
  firstName: "Bob"
  lastName: "Jensen"
  email: "bjensen@example.com"
  active: true
  _auth:
    authenticatedBy: "OAuth2"
    tier: "premium"
```

---

## Category 17: Reusable Mappers (FR-001-08)

Mapper definitions and `mapperRef` resolution within transform specs.


---

### S-001-50: Apply Directive — Mapper + Expr + Mapper Pipeline


---

```yaml
scenario: S-001-50
name: apply-directive-mapper-pipeline
description: >
  A transform spec defines named mappers under the `mappers` block and uses the
  `apply` directive to sequence them with the main `expr`. The engine executes
  steps in declaration order: strip-internal → expr → add-metadata.
  Validates FR-001-08 happy path and ADR-0014.
tags: [mappers, mapperRef, apply, resolution, fr-001-08, adr-0014]
refs: [FR-001-08]

spec:
  id: order-transform
  version: "1.0.0"
  mappers:
    strip-internal:
      lang: jslt
      expr: |
        { * : . }
        - "_internal_id"
        - "_debug_trace"
    add-metadata:
      lang: jslt
      expr: |
        . + {
          "_meta": {
            "transformedBy": "message-xform",
            "specVersion": "1.0.0"
          }
        }
  transform:
    lang: jslt
    expr: |
      {
        "orderId": .orderId,
        "amount": .amount,
        "currency": .currency
      }
    apply:
      - mapperRef: strip-internal       # step 1: strip internal fields
      - expr                            # step 2: restructure body
      - mapperRef: add-metadata         # step 3: add gateway metadata

input:
  orderId: "ord-123"
  amount: 99.50
  currency: "EUR"
  _internal_id: 88412
  _debug_trace: "svc=orders,dur=8ms"

expected_output:
  orderId: "ord-123"
  amount: 99.50
  currency: "EUR"
  _meta:
    transformedBy: "message-xform"
    specVersion: "1.0.0"
```
---


### S-001-51: Missing mapperRef in Apply Directive Rejected at Load Time


---

```yaml
scenario: S-001-51
name: mapper-ref-missing-rejected
description: >
  A transform spec's `apply` directive references a mapper id that does not exist
  in the `mappers` block. The engine MUST reject the spec at load time with a
  descriptive error. Validates FR-001-08 validation path and ADR-0014.
tags: [mappers, mapperRef, apply, validation, error, fr-001-08, adr-0014]
refs: [FR-001-08]

spec:
  id: broken-spec
  version: "1.0.0"
  mappers:
    strip-internal:
      lang: jslt
      expr: '{ * : . } - "_debug"'
  transform:
    lang: jslt
    expr: '{ "id": .id }'
    apply:
      - mapperRef: strip-internal
      - expr
      - mapperRef: does-not-exist       # <- unknown mapper id

expected_error:
  phase: load
  type: MapperResolutionError
  message_contains: "does-not-exist"
```
---


### S-001-52: Duplicate mapperRef in Apply Directive Rejected at Load Time


---

```yaml
scenario: S-001-52
name: mapper-ref-duplicate-rejected
description: >
  The `apply` directive references the same mapper id more than once.
  Under ADR-0014's flat model, mappers are standalone expressions composed
  via `apply` — there is no mapper-to-mapper cross-referencing, so true
  circular dependencies are structurally impossible. However, duplicate
  mapperRef entries in the apply list are rejected at load time to prevent
  accidental double-application.
  Validates FR-001-08 validation path and ADR-0014.
tags: [mappers, mapperRef, duplicate, validation, error, fr-001-08, adr-0014]
refs: [FR-001-08]

spec:
  id: duplicate-ref-spec
  version: "1.0.0"
  mappers:
    strip-internal:
      lang: jslt
      expr: '{ * : . } - "_debug"'
  transform:
    lang: jslt
    expr: '{ "id": .id }'
    apply:
      - mapperRef: strip-internal
      - expr
      - mapperRef: strip-internal       # <- duplicate mapper id

expected_error:
  phase: load
  type: SpecParseException
  message_contains: "appears more than once in apply"
```
---


### S-001-59: Apply Directive Without `expr` Rejected at Load Time


---

```yaml
scenario: S-001-59
name: apply-directive-missing-expr-rejected
description: >
  A transform spec defines an `apply` directive that lists only mapperRef steps
  but omits `expr`. The engine MUST reject at load time because `expr` must
  appear exactly once in the apply list. Validates FR-001-08 / ADR-0014.
tags: [mappers, apply, validation, error, fr-001-08, adr-0014]
refs: [FR-001-08]

spec:
  id: no-expr-spec
  version: "1.0.0"
  mappers:
    strip-internal:
      lang: jslt
      expr: '{ * : . } - "_debug"'
    add-meta:
      lang: jslt
      expr: '. + { "_meta": { "ok": true } }'
  transform:
    lang: jslt
    expr: '{ "id": .id }'
    apply:
      - mapperRef: strip-internal
      - mapperRef: add-meta             # no `expr` step!

expected_error:
  phase: load
  type: ApplyDirectiveError
  message_contains: "expr"
```

---

## Category 18: Schema Validation (FR-001-09 / ADR-0001)

Input/output JSON Schema validation at load time and runtime.


---

### S-001-53: Valid Schemas Accepted at Load Time


---

```yaml
scenario: S-001-53
name: schema-valid-load-time
description: >
  A transform spec declares valid JSON Schema 2020-12 for both input and output.
  The engine parses and stores the schemas at load time without error.
  Validates FR-001-09 and ADR-0001 happy path.
tags: [schema, validation, load-time, fr-001-09, adr-0001]
refs: [FR-001-09]

spec:
  id: validated-transform
  version: "1.0.0"
  input:
    schema:
      type: object
      required: [userId, email]
      properties:
        userId: { type: string, pattern: "^usr-" }
        email: { type: string, format: email }
        role: { type: string, enum: [admin, user, guest] }
  output:
    schema:
      type: object
      required: [id, emailAddress]
      properties:
        id: { type: string }
        emailAddress: { type: string }
  transform:
    lang: jslt
    expr: |
      {
        "id": .userId,
        "emailAddress": .email
      }

expected_load_result: success
```
---


### S-001-54: Invalid Schema Rejected at Load Time


---

```yaml
scenario: S-001-54
name: schema-invalid-rejected
description: >
  A transform spec declares a syntactically invalid JSON Schema (unknown type
  keyword, malformed structure). The engine MUST reject the spec at load time
  with a descriptive error.
  Validates FR-001-09 and ADR-0001 validation path.
tags: [schema, validation, error, load-time, fr-001-09, adr-0001]
refs: [FR-001-09]

spec:
  id: bad-schema-spec
  version: "1.0.0"
  input:
    schema:
      type: not-a-valid-type  # invalid JSON Schema type
      required: 42             # must be an array, not integer
  output:
    schema:
      type: object
  transform:
    lang: jslt
    expr: '.'

expected_error:
  phase: load
  type: SchemaValidationError
  message_contains: "input.schema"
```
---


### S-001-55: Strict-Mode Runtime Schema Validation Failure


---

```yaml
scenario: S-001-55
name: schema-strict-mode-runtime-failure
description: >
  In strict mode, the engine validates the input against `input.schema` before
  evaluation. When the input does not conform, the engine aborts the transform
  and passes the original message through.
  Validates FR-001-09 strict-mode runtime path and ADR-0001.
tags: [schema, validation, strict-mode, runtime, fr-001-09, adr-0001]
refs: [FR-001-09]

config:
  schema_validation: strict   # enable runtime validation

spec:
  id: validated-transform
  version: "1.0.0"
  input:
    schema:
      type: object
      required: [userId, email]
      properties:
        userId: { type: string }
        email: { type: string, format: email }
  output:
    schema:
      type: object
  transform:
    lang: jslt
    expr: '{ "id": .userId }'

# Input does NOT conform: missing required 'email', 'userId' is integer not string
input:
  userId: 42
  name: "Bob Jensen"

# In strict mode, schema validation fails → error response returned (ADR-0022)
expected_error_response:
  type: "urn:message-xform:error:schema-validation-failed"
  title: "Schema Validation Failed"
  status: 502
  detail_contains: "schema validation failed"

expected_log:
  level: WARN
  message_contains: "schema validation failed"
  spec_id: validated-transform
```

---

## Category 19: Pipeline Chaining & Message Semantics

Scenarios validating profile-level chaining (ADR-0012), TransformContext (DO-001-07),
and copy-on-wrap adapter semantics (ADR-0013).


---

### S-001-56: Pipeline Chain Abort on Failure

When a chain of two specs is configured and the first spec fails, the entire chain
aborts and returns an error response (ADR-0022).


---

```yaml
scenario: S-001-56
name: pipeline-chain-abort-on-failure
description: >
  Profile chains spec-a → spec-b. spec-a has a deliberate error (references
  undefined variable). The entire chain must abort — error response returned,
  spec-b never executes. Original message is NOT passed through (ADR-0022).
tags: [profile-chaining, abort, error, adr-0012, adr-0013]
refs: [FR-001-05, FR-001-07]

profile:
  transforms:
    - spec: spec-a@1.0.0
      direction: response
      match: { path: "/api/orders" }
    - spec: spec-b@1.0.0
      direction: response
      match: { path: "/api/orders" }

specs:
  spec-a:
    lang: jslt
    expr: |
      { "result": $undefined_variable }
  spec-b:
    lang: jslt
    expr: |
      { "enriched": true, * : . }

input:
  orderId: 123
  items: ["widget"]

# spec-a fails → entire chain aborts → error response returned (ADR-0022)
expected_error_response:
  type: "urn:message-xform:error:transform-failed"
  title: "Transform Failed"
  status: 502
  detail_contains: "chain aborted at step 1/2"
  spec_id: spec-a
```
---


### S-001-57: TransformContext — $headers Available in Body Expression

The JSLT body expression can access `$headers` to inject header values into the
transformed body.


---

```yaml
scenario: S-001-57
name: transform-context-headers-in-body
description: >
  Validates DO-001-07 (TransformContext): the $headers variable is bound during
  JSLT body evaluation, allowing header values to be embedded in the output body.
tags: [transform-context, headers, body, do-001-07]
refs: [FR-001-02, FR-001-10]

transform:
  lang: jslt
  expr: |
    {
      "userId": .userId,
      "requestedBy": $headers.X-Requested-By,
      "correlationId": $headers.X-Correlation-Id
    }

context:
  headers:
    X-Requested-By: "frontend-app"
    X-Correlation-Id: "abc-123"

input:
  userId: 42
  name: "Alice"

expected_output:
  userId: 42
  requestedBy: "frontend-app"
  correlationId: "abc-123"
```
---


### S-001-58: Copy-on-Wrap — Failed Transform Returns Error Response

Validates that on transform failure, the adapter's copy is discarded and an
error response is returned to the caller (ADR-0022).


---

```yaml
scenario: S-001-58
name: copy-on-wrap-abort-error-response
description: >
  Validates ADR-0013 (copy-on-wrap) + ADR-0022 (error-response-on-failure):
  when a transform fails (e.g., JSLT error), the Message copy is discarded
  and an error response is returned to the caller. The native gateway message
  is NOT forwarded.
tags: [copy-on-wrap, abort, error-response, adr-0013, adr-0022]
refs: [FR-001-04, FR-001-07]

transform:
  lang: jslt
  expr: |
    { "result": .nonexistent.deeply.nested.path.method() }

headers:
  add:
    X-Transform-Applied: "true"

input:
  userId: 42

# Transform fails → copy discarded → error response returned
expected_error_response:
  type: "urn:message-xform:error:transform-failed"
  title: "Transform Failed"
  status: 502
  detail_contains: "expression evaluation failed"

expected_native_state:
  headers_unchanged: true
  status_unchanged: true
  not_forwarded: true
```

---

### S-001-60: Direction-Agnostic Spec Bound to Both Directions

Validates that the same unidirectional spec can be bound to both `request` and
`response` directions in a profile (ADR-0016).


---

```yaml
scenario: S-001-60
name: direction-agnostic-both-bindings
description: >
  A unidirectional spec with only `transform` is bound to both `direction: response`
  and `direction: request` in the same profile. The engine applies the same expression
  in both phases. Validates ADR-0016.
tags: [direction, agnostic, profile, adr-0016]
refs: [FR-001-03]

spec:
  id: strip-debug
  version: "1.0.0"
  transform:
    lang: jslt
    expr: |
      { * : . } - "_debug"

profile:
  id: cleanup-profile
  transforms:
    - spec: strip-debug@1.0.0
      direction: response
      match:
        path: "/api/users"
    - spec: strip-debug@1.0.0
      direction: request
      match:
        path: "/api/admin"

# Response direction test:
input_response:
  userId: 42
  _debug: "trace-abc"

expected_output_response:
  userId: 42

# Request direction test (same spec, same logic):
input_request:
  action: "delete"
  _debug: "trace-xyz"

expected_output_request:
  action: "delete"
```

---

### S-001-61: `$status` is Null in Request Transform

Validates that `$status` is `null` during request-phase transforms (ADR-0017).


---

```yaml
scenario: S-001-61
name: status-null-in-request-transform
description: >
  A spec references `$status` in its expression. When bound to `direction: request`,
  `$status` is `null` because no HTTP status exists yet. The expression guards with
  `if ($status)`. Validates ADR-0017.
tags: [status, null, request, direction, adr-0017]
refs: [FR-001-11, FR-001-03]

spec:
  id: status-aware
  version: "1.0.0"
  transform:
    lang: jslt
    expr: |
      {
        "data": .data,
        "statusKnown": $status != null,
        "httpStatus": if ($status) $status else "N/A"
      }

# Bound to request direction → $status is null
profile_direction: request

input:
  data: "hello"

expected_output:
  data: "hello"
  statusKnown: false
  httpStatus: "N/A"
```

---

### S-001-62: Sensitive Field Path Syntax Validation

Validates that invalid JSON path syntax in the `sensitive` block is rejected at
load time (ADR-0019).


---

```yaml
scenario: S-001-62
name: sensitive-path-invalid-syntax-rejected
description: >
  A spec declares a `sensitive` list with an invalid JSON path expression (missing
  `$` prefix). The engine MUST reject at load time with a descriptive error.
  Validates ADR-0019 / NFR-001-06.
tags: [sensitive, validation, error, adr-0019, nfr-001-06]
refs: [FR-001-01]

spec:
  id: bad-sensitive-spec
  version: "1.0.0"
  sensitive:
    - "$.authId"                        # valid
    - "callbacks[*].input"              # INVALID — missing $ prefix

  transform:
    lang: jslt
    expr: '{ "id": .id }'

expected_error:
  phase: load
  type: SensitivePathSyntaxError
  message_contains: "callbacks[*].input"
```

---

### S-001-75: Valid Sensitive Paths Parsed and Stored

Validates that valid JSON path expressions in the `sensitive` block are parsed
and stored on the `TransformSpec` at load time (ADR-0019, NFR-001-06).


---

```yaml
scenario: S-001-75
name: sensitive-paths-valid-parsed
description: >
  A spec declares a `sensitive` list with valid RFC 9535 JSON path expressions.
  The engine MUST accept the spec and store the paths on TransformSpec.sensitivePaths.
  Validates ADR-0019 / NFR-001-06 (happy path).
tags: [sensitive, validation, load-time, adr-0019, nfr-001-06]
refs: [FR-001-01]

spec:
  id: valid-sensitive-spec
  version: "1.0.0"
  sensitive:
    - "$.authId"
    - "$.credentials.password"
    - "$.callbacks[*].input[*].value"

  input:
    schema:
      type: object
  output:
    schema:
      type: object

  transform:
    lang: jslt
    expr: '.'

expected:
  load_succeeds: true
  sensitive_paths:
    - "$.authId"
    - "$.credentials.password"
    - "$.callbacks[*].input[*].value"
```

---

### S-001-63: Nullable Status Code — Integer Contract

Validates that `$status` is properly null (not -1 or any sentinel) for request
transforms, using the nullable `Integer` contract (ADR-0020).


---

```yaml
scenario: S-001-63
name: nullable-status-integer-contract
description: >
  A spec uses $status in a body expression. When bound to direction: request,
  $status is null (not -1). The expression tests strict null equality.
  Validates ADR-0020 superseding int/-1 sentinel.
tags: [status, null, nullable, integer, adr-0020]
refs: [FR-001-11, FR-001-02]

spec:
  id: status-type-check
  version: "1.0.0"
  transform:
    lang: jslt
    expr: |
      {
        "statusIsNull": $status == null,
        "statusType": if ($status == null) "absent" else "present",
        "data": .data
      }

# Bound to request direction → $status must be null, not -1
profile_direction: request

input:
  data: "test"

expected_output:
  statusIsNull: true
  statusType: "absent"
  data: "test"

# CRITICAL: $status must NOT be -1. Testing that null != -1:
negative_assertion:
  statusType_must_not_be: "present"
  # If engine incorrectly maps -1 as non-null, statusType would be "present"
```

---

### S-001-64: Query Params in Body Expression

Validates that `$queryParams` is accessible in JSLT body expressions, allowing
specs to branch on URL query parameters (ADR-0021).


---

```yaml
scenario: S-001-64
name: query-params-in-body-expression
description: >
  A spec references $queryParams to branch transformation logic based on the
  authIndexType query parameter. Validates ADR-0021.
tags: [query-params, context, branching, adr-0021]
refs: [FR-001-02]

spec:
  id: auth-type-aware
  version: "1.0.0"
  transform:
    lang: jslt
    expr: |
      {
        "authType": $queryParams."authIndexType",
        "authValue": $queryParams."authIndexValue",
        "isServiceAuth": $queryParams."authIndexType" == "service",
        "data": .data
      }

profile_direction: request
request_path: "/json/alpha/authenticate"
request_query: "authIndexType=service&authIndexValue=Login"

input:
  data: "hello"

expected_output:
  authType: "service"
  authValue: "Login"
  isServiceAuth: true
  data: "hello"

expected_context:
  queryParams:
    authIndexType: "service"
    authIndexValue: "Login"
```

---

### S-001-65: Request Cookies in Body Expression

Validates that `$cookies` is accessible in JSLT body expressions, exposing
request-side cookies as key-value pairs (ADR-0021).


---

```yaml
scenario: S-001-65
name: cookies-in-body-expression
description: >
  A spec references $cookies to read the user's language preference from a
  request cookie and include it in the transformed output. Validates ADR-0021.
tags: [cookies, context, adr-0021]
refs: [FR-001-02]

spec:
  id: locale-aware-transform
  version: "1.0.0"
  transform:
    lang: jslt
    expr: |
      {
        "locale": if ($cookies."lang") $cookies."lang" else "en-US",
        "theme": if ($cookies."theme") $cookies."theme" else "light",
        "data": .data
      }

profile_direction: request
request_headers:
  Cookie: "lang=nl-NL; theme=dark; session=eyJhbGciOi..."

input:
  data: "hello"

expected_output:
  locale: "nl-NL"
  theme: "dark"
  data: "hello"

expected_context:
  cookies:
    lang: "nl-NL"
    theme: "dark"
    session: "eyJhbGciOi..."
```

---

## Category 20: Error Type Catalogue (ADR-0024)

Scenarios validating that the engine exposes the correct exception types to adapters
and that error responses carry the correct URN `type` values.


---

### S-001-66: Load-Time Error Type Discrimination

Adapters must be able to distinguish different load-time failure causes by exception
type (`TransformLoadException` subtypes).


---

```yaml
scenario: S-001-66
name: load-time-error-type-discrimination
description: >
  Three different broken specs are loaded: one with invalid YAML, one with a bad
  JSLT expression, one with an invalid JSON Schema. Each must produce a distinct
  exception subtype of TransformLoadException, enabling the adapter to log and
  respond specifically. Validates ADR-0024 load-time tier.
tags: [error-catalogue, load-time, exception-hierarchy, adr-0024]
refs: [FR-001-07]

# Spec 1: Invalid YAML
spec_yaml_broken:
  raw: |
    id: bad-yaml
    version: "1.0.0"
    transform:
      lang: jslt
      expr: |
        this line has no colon and breaks yaml
           bad_indent: true

expected_error_1:
  phase: load
  type: SpecParseException
  parent_type: TransformLoadException
  message_contains: "YAML"

# Spec 2: Invalid JSLT expression
spec_jslt_broken:
  id: bad-jslt
  version: "1.0.0"
  transform:
    lang: jslt
    expr: 'if (.x "bad"'   # missing closing paren

expected_error_2:
  phase: load
  type: ExpressionCompileException
  parent_type: TransformLoadException
  message_contains: "compile"

# Spec 3: Invalid JSON Schema
spec_schema_broken:
  id: bad-schema
  version: "1.0.0"
  input:
    schema:
      type: not-a-valid-type
      required: 42
  transform:
    lang: jslt
    expr: '.'

expected_error_3:
  phase: load
  type: SchemaValidationException
  parent_type: TransformLoadException
  message_contains: "schema"
```
---


### S-001-67: Evaluation Budget Exceeded → EvalBudgetExceededException


---

```yaml
scenario: S-001-67
name: eval-budget-exceeded-exception-type
description: >
  An expression exceeds the configured max-eval-ms budget. The engine must throw
  EvalBudgetExceededException (a TransformEvalException subtype) and return an
  error response with URN type 'urn:message-xform:error:eval-budget-exceeded'.
  Validates ADR-0024 evaluation-time tier.
tags: [error-catalogue, eval-time, budget, exception-hierarchy, adr-0024]
refs: [FR-001-07]

config:
  engines:
    defaults:
      max-eval-ms: 1    # artificially low budget to force timeout

transform:
  lang: jslt
  expr: |
    {
      "result": [for (1,2,3,4,5,6,7,8,9,10)
        [for (1,2,3,4,5,6,7,8,9,10)
          [for (1,2,3,4,5,6,7,8,9,10) .]
        ]
      ]
    }

input:
  value: "test"

expected_error_response:
  type: "urn:message-xform:error:eval-budget-exceeded"
  title: "Transform Failed"
  status: 502
  detail_contains: "budget exceeded"

expected_exception:
  type: EvalBudgetExceededException
  parent_type: TransformEvalException
  chain_step: null
```
---


### S-001-68: YAML Parse Error → SpecParseException with Source Path

Validates that `SpecParseException` carries the `source` field pointing to the
failing file path.


---

```yaml
scenario: S-001-68
name: spec-parse-exception-source-path
description: >
  A spec file at a known path contains invalid YAML. The thrown
  SpecParseException must carry the `source` field with the file path,
  enabling the adapter to log exactly which file failed.
  Validates ADR-0024 common exception fields.
tags: [error-catalogue, load-time, source-path, adr-0024]
refs: [FR-001-07]

spec_path: "/config/specs/broken-spec.yaml"
spec_content: |
  id: broken
  version: 1.0.0
  transform:
    - this is not valid yaml for a transform block

expected_error:
  phase: load
  type: SpecParseException
  source: "/config/specs/broken-spec.yaml"
  message_contains: "YAML"
```

---

## Category 21: Multi-Value Header Access (ADR-0026)

Scenarios validating that `$headers_all` exposes all header values as arrays.


---

### S-001-69: Multi-Value Set-Cookie via `$headers_all`


---

```yaml
scenario: S-001-69
name: headers-all-set-cookie-multi-value
description: >
  A response carries two Set-Cookie headers. $headers returns only the first
  value, while $headers_all returns both as an array. Validates ADR-0026.
tags: [headers, multi-value, headers-all, set-cookie, adr-0026]
refs: [FR-001-10]

transform:
  lang: jslt
  expr: |
    {
      "firstCookie": $headers."Set-Cookie",
      "allCookies": $headers_all."Set-Cookie",
      "cookieCount": size($headers_all."Set-Cookie"),
      "singleHeader": $headers_all."Content-Type",
      "data": .data
    }

context:
  headers:
    Set-Cookie: ["session=abc123; Path=/", "lang=en; Path=/"]
    Content-Type: ["application/json"]

input:
  data: "hello"

expected_output:
  firstCookie: "session=abc123; Path=/"
  allCookies: ["session=abc123; Path=/", "lang=en; Path=/"]
  cookieCount: 2
  singleHeader: ["application/json"]
  data: "hello"
```
---


### S-001-70: `$headers_all` Missing Header Returns Null


---

```yaml
scenario: S-001-70
name: headers-all-missing-returns-null
description: >
  When a header is not present, $headers_all returns null (not an empty array).
  This is consistent with $headers behaviour. Validates ADR-0026.
tags: [headers, multi-value, headers-all, null, edge-case, adr-0026]
refs: [FR-001-10]

transform:
  lang: jslt
  expr: |
    {
      "existing": $headers_all."Content-Type",
      "missing": $headers_all."X-Does-Not-Exist",
      "hasMissing": $headers_all."X-Does-Not-Exist" != null,
      "data": .data
    }

context:
  headers:
    Content-Type: ["application/json"]

input:
  data: "test"

expected_output:
  existing: ["application/json"]
  missing: null
  hasMissing: false
  data: "test"
```
---


### S-001-71: X-Forwarded-For Chain via `$headers_all`


---

```yaml
scenario: S-001-71
name: headers-all-x-forwarded-for-chain
description: >
  X-Forwarded-For carries multiple IPs through proxy layers. $headers_all
  exposes all values. The spec extracts the original client IP (first element).
  Validates ADR-0026.
tags: [headers, multi-value, headers-all, x-forwarded-for, adr-0026]
refs: [FR-001-10]

transform:
  lang: jslt
  expr: |
    {
      "clientIp": $headers_all."X-Forwarded-For"[0],
      "proxyChain": $headers_all."X-Forwarded-For",
      "hopCount": size($headers_all."X-Forwarded-For"),
      "data": .data
    }

context:
  headers:
    X-Forwarded-For: ["192.168.1.1", "10.0.0.1", "172.16.0.1"]

input:
  data: "request"

expected_output:
  clientIp: "192.168.1.1"
  proxyChain: ["192.168.1.1", "10.0.0.1", "172.16.0.1"]
  hopCount: 3
  data: "request"
```

---

### S-001-72: Header Case Normalization


---

```yaml
scenario: S-001-72
name: header-case-normalization
description: >
  Headers are normalized to lowercase when bound to $headers and $headers_all.
  JSLT expressions reference lowercase names regardless of the original casing
  provided by the gateway. Validates FR-001-10 header normalization rule.
tags: [headers, case-insensitive, normalization, rfc9110]
refs: [FR-001-10]

transform:
  lang: jslt
  expr: |
    {
      "contentType": $headers."content-type",
      "auth": $headers."authorization",
      "allAuth": $headers_all."authorization"
    }

context:
  headers:
    Content-Type: ["application/json"]
    Authorization: ["Bearer abc123"]

input:
  data: "test"

expected_output:
  contentType: "application/json"
  auth: "Bearer abc123"
  allAuth: ["Bearer abc123"]
```

---

### S-001-73: Chain Direction Conflict — Rejected at Load Time


---

```yaml
scenario: S-001-73
name: chain-direction-conflict-rejected
description: >
  A profile where two entries match the same path/method but declare
  conflicting directions (one 'request', one 'response') MUST be rejected
  at load time. Validates FR-001-05 direction consistency rule.
tags: [profile, chain, direction, validation, error, load-time]
refs: [FR-001-05]

profile:
  id: conflicting-directions
  version: "1.0.0"
  transforms:
    - spec: spec-a
      direction: response
      match:
        path: "/api/v1/test"
        method: POST
    - spec: spec-b
      direction: request      # conflicts with spec-a's direction on same route
      match:
        path: "/api/v1/test"
        method: POST

expected_behaviour: LOAD_TIME_REJECTION
expected_error_type: TransformLoadException
error_detail_contains: "conflicting directions"
```

---

### S-001-74: Chain Step Logging — Structured Fields per Step

Validates that each chain step emits structured log entries with `chain_step`,
`spec_id`, and `profile_id` (NFR-001-08, T-001-33).


---

```yaml
scenario: S-001-74
name: chain-step-structured-logging
description: >
  A profile with 3 matching entries is executed. The engine MUST emit structured
  log entries for each step containing: chain_step (e.g. "2/3"), spec_id, and
  profile_id. Start and completion events MUST also be logged.
  Validates NFR-001-08 chain-level logging.
tags: [chain-step-logging, structured-logging, nfr-001-08, profile-chaining]
refs: [FR-001-05]

profile:
  id: log-test-profile
  transforms:
    - spec: step-1@1.0.0
      direction: response
      match: { path: "/api/chain", method: POST }
    - spec: step-2@1.0.0
      direction: response
      match: { path: "/api/chain", method: POST }
    - spec: step-3@1.0.0
      direction: response
      match: { path: "/api/chain", method: POST }

expected_log_entries:
  - message_contains: "Starting chain execution"
    fields: { profile_id: "log-test-profile", chain_steps: 3 }
  - message_contains: "Executing chain step"
    fields: { chain_step: "1/3", spec_id: "step-1", profile_id: "log-test-profile" }
  - message_contains: "Executing chain step"
    fields: { chain_step: "2/3", spec_id: "step-2", profile_id: "log-test-profile" }
  - message_contains: "Executing chain step"
    fields: { chain_step: "3/3", spec_id: "step-3", profile_id: "log-test-profile" }
  - message_contains: "Chain execution complete"
    fields: { profile_id: "log-test-profile", steps: 3 }
```

---

## Category 22: Hot Reload & Atomic Registry Swap (NFR-001-05)

Scenarios validating the immutable `TransformRegistry` snapshot, atomic swap via
`TransformEngine.reload()`, and fail-safe behaviour when reload encounters errors.


---

### S-001-76: Reload Swaps Registry Atomically


---

```yaml
scenario: S-001-76
name: reload-swaps-registry-atomically
description: >
  After loading spec v1, the engine transforms using v1. After calling reload()
  with spec v2, the engine transforms using v2 — v1 is no longer available.
  The old registry is entirely replaced, not merged.
  Validates NFR-001-05 (atomic registry swap).
tags: [hot-reload, atomic-swap, registry, nfr-001-05]
refs: [NFR-001-05]

phase_1:
  specs:
    - id: swap-test
      version: "1.0.0"
      transform:
        lang: jslt
        expr: '{ "old_name": .name }'

  input: { name: "alice" }
  expected_output: { old_name: "alice" }

phase_2_reload_with:
  specs:
    - id: swap-test
      version: "2.0.0"
      transform:
        lang: jslt
        expr: '{ "new_name": .name }'

  input: { name: "alice" }
  expected_output: { new_name: "alice" }
  not_present: ["old_name"]
```
---


### S-001-77: Fail-Safe Reload Preserves Old Registry on Error


---

```yaml
scenario: S-001-77
name: fail-safe-reload-preserves-old
description: >
  A working engine with a valid spec receives a reload() call with a broken spec
  (missing required 'id' field). The reload fails with an exception. The engine
  continues serving with the OLD registry — no data loss or broken state.
  Validates NFR-001-05 (fail-safe reload).
tags: [hot-reload, fail-safe, error, registry, nfr-001-05]
refs: [NFR-001-05]

phase_1:
  specs:
    - id: good-spec
      version: "1.0.0"
      transform:
        lang: jslt
        expr: '{ "result": .name }'

  input: { name: "alice" }
  expected_output: { result: "alice" }

phase_2_reload_with:
  specs:
    - broken: true  # missing 'id' field → SpecParseException
      version: "1.0.0"
      transform:
        lang: jslt
        expr: '{ "result": .name }'

  expected_error:
    phase: reload
    type: SpecParseException

  # After failed reload, engine still serves with old registry
  post_reload_input: { name: "alice" }
  post_reload_expected_output: { result: "alice" }
```
---


### S-001-78: Concurrent Reads During Swap See Consistent Snapshot


---

```yaml
scenario: S-001-78
name: concurrent-reads-consistent-snapshot
description: >
  Multiple threads read from the engine while reload() is called mid-flight.
  Each thread must observe either the old OR the new registry — never a mix
  of old specs and new specs within a single transform call.
  Validates NFR-001-05 (thread-safe atomic swap).
tags: [hot-reload, concurrent, thread-safety, atomic-swap, nfr-001-05]
refs: [NFR-001-05]

phase_1:
  specs:
    - id: concurrent-test
      version: "1.0.0"
      transform:
        lang: jslt
        expr: '{ "version": "v1" }'

concurrent_readers: 10
iterations_per_reader: 50

phase_2_reload_with:
  specs:
    - id: concurrent-test
      version: "2.0.0"
      transform:
        lang: jslt
        expr: '{ "version": "v2" }'

# Each reader must see EITHER { "version": "v1" } OR { "version": "v2" }
# in each iteration — never a mix or inconsistent state.
assertion: each_result_version_is_one_of ["v1", "v2"]
```

---

## Category 23: Performance Benchmarks (NFR-001-03, ADR-0028)

Opt-in benchmark scenarios verifying NFR-001-03 (transformation p95 latency < 5ms
for ≤ 50KB payloads). Enabled via `-Dio.messagexform.benchmark=true` or
`IO_MESSAGEXFORM_BENCHMARK=true`. See ADR-0028 for the hybrid performance testing
strategy.


---

### S-001-79: Identity JSLT Transform — p95 < 5ms (1KB body)


---

```yaml
scenario: S-001-79
name: benchmark-identity-1kb
description: >
  Identity JSLT pass-through transform ("." expression) with a ~1KB payload.
  Measures p50, p90, p95, p99, max latency and throughput.
  p95 MUST be < 5ms per NFR-001-03.
tags: [benchmark, performance, identity, nfr-001-03, adr-0028]
refs: [NFR-001-03]

transform:
  lang: jslt
  expr: "."

payload_size: ~1KB
warmup_iterations: 2000
measured_iterations: 20000

assert:
  p95_latency_ms: "< 5.0"
```
---


### S-001-80: 5-Field Mapping JSLT Transform — p95 < 5ms (10KB body)


---

```yaml
scenario: S-001-80
name: benchmark-field-mapping-10kb
description: >
  5-field rename/restructure transform with a ~10KB payload.
  Representative of typical API versioning use cases.
  p95 MUST be < 5ms per NFR-001-03.
tags: [benchmark, performance, field-mapping, nfr-001-03, adr-0028]
refs: [NFR-001-03]

transform:
  lang: jslt
  expr: |
    {
      "userId": .user_id,
      "firstName": .first_name,
      "lastName": .last_name,
      "emailAddress": .email_address,
      "isActive": .is_active
    }

payload_size: ~10KB
warmup_iterations: 2000
measured_iterations: 20000

assert:
  p95_latency_ms: "< 5.0"
```
---


### S-001-81: Complex Nested/Array Transform — p95 < 5ms (50KB body)


---

```yaml
scenario: S-001-81
name: benchmark-complex-50kb
description: >
  Complex transform with nested objects and array iteration (SCIM-style).
  ~50KB payload — worst-case NFR-001-03 scenario.
  p95 MUST be < 5ms per NFR-001-03.
tags: [benchmark, performance, complex, array, nfr-001-03, adr-0028]
refs: [NFR-001-03]

transform:
  lang: jslt
  expr: |
    {
      "total": .totalResults,
      "users": [for (.Resources)
        {
          "id": .id,
          "username": .userName,
          "displayName": .displayName,
          "email": .emails[0].value,
          "active": .active,
          "status": if (.active) "enabled" else "disabled",
          "role": if (.role) .role else "user"
        }
      ]
    }

payload_size: ~50KB
warmup_iterations: 2000
measured_iterations: 20000

assert:
  p95_latency_ms: "< 5.0"
```


---

## Category 24: Session Context Binding

Scenarios for gateway-provided session context (`$session`). See ADR-0030.


---

### S-001-82: Inject Session Subject into Request Body

The gateway provides session context with `sub` claim. The transform injects it
into the request body as `userId`.


---

```yaml
scenario: S-001-82
name: session-inject-subject-into-body
description: >
  Gateway session contains sub claim from a validated token. Transform injects
  it into the request body as userId. Tests basic $session binding in request
  direction.
tags: [session, inject, request, adr-0030]
refs: [FR-001-13, ADR-0030]

transform:
  lang: jslt
  expr: |
    . + {
      "userId": $session.sub,
      "tenantId": $session.tenantId
    }

# Session context provided by the gateway adapter
session_context:
  sub: "bjensen"
  tenantId: "acme-corp"
  roles: ["admin", "user"]

input:
  action: "create-order"
  amount: 99.50
  currency: "EUR"

expected_output:
  action: "create-order"
  amount: 99.50
  currency: "EUR"
  userId: "bjensen"
  tenantId: "acme-corp"
```
---


### S-001-83: Conditional Response Based on Session Roles

The transform uses `$session.roles` to conditionally include admin-only fields
in the response body.


---

```yaml
scenario: S-001-83
name: session-conditional-response-by-role
description: >
  Gateway session contains roles array. Transform conditionally includes
  sensitive admin fields in the response only when the session has the admin
  role. Tests $session in response direction with conditional logic.
tags: [session, conditional, response, adr-0030]
refs: [FR-001-13, ADR-0030]

transform:
  lang: jslt
  expr: |
    {
      "id": .id,
      "name": .name,
      "email": .email,
      "adminPanel": if (contains("admin", $session.roles)) .adminData else null
    }

session_context:
  sub: "bjensen"
  roles: ["admin", "user"]

input:
  id: "usr-42"
  name: "Bob Jensen"
  email: "bjensen@example.com"
  adminData:
    lastLogin: "2026-02-09T08:00:00Z"
    failedAttempts: 0

expected_output:
  id: "usr-42"
  name: "Bob Jensen"
  email: "bjensen@example.com"
  adminPanel:
    lastLogin: "2026-02-09T08:00:00Z"
    failedAttempts: 0
```
---


### S-001-84: Null Session Context — Null-Safe Access

When no session context is provided by the gateway, `$session` is `null`.
JSLT handles null gracefully — accessing fields on null returns null.


---

```yaml
scenario: S-001-84
name: session-null-safe-access
description: >
  No session context provided by gateway ($session is null). Transform
  gracefully handles null session — JSLT null-access returns null, which
  JSLT omits from output. Tests null-safety of $session binding.
tags: [session, null, edge-case, adr-0030]
refs: [FR-001-13, ADR-0030]

transform:
  lang: jslt
  expr: |
    {
      "action": .action,
      "userId": $session.sub,
      "amount": .amount
    }

# session_context: null (not provided by gateway)

input:
  action: "create-order"
  amount: 99.50

expected_output:
  action: "create-order"
  # Note: JSLT omits keys whose value is null — no userId key here
  amount: 99.50
```
---


### S-001-85: JOLT Engine with $session Reference — Rejected at Load Time

JOLT does not support context variables. A spec declaring `lang: jolt` that
references `$session` must be rejected at load time.


---

```yaml
scenario: S-001-85
name: session-jolt-rejected-at-load-time
description: >
  Spec declares lang: jolt but the expression references $session. JOLT does
  not support context variables. Engine MUST reject at load time.
tags: [session, jolt, validation, load-time, adr-0030]
refs: [FR-001-13, ADR-0030]

transform:
  lang: jolt
  expr: |
    [
      {
        "operation": "shift",
        "spec": { "*": "&" }
      }
    ]

# The spec itself doesn't reference $session in the JOLT spec (JOLT can't),
# but any explicit $session reference in a JOLT-engine context would be
# rejected at load time by the engine capability check (ADR-0004).

expected_error:
  type: load-time
  exception: ExpressionCompileException
  message_contains: "does not support context variables"
```

## Category 25: Conditional Response Routing (FR-001-15, FR-001-16, ADR-0036)

Scenarios for status-code pattern matching (`match.status`) and body-predicate
matching (`match.when`) in profile entries. Phase 1 covers status matching;
Phase 2 covers body predicates.

### S-001-87: Status Class Routing — 2xx Matches, 4xx Doesn't

Response-direction profile entries with `match.status: "2xx"` match any 200-level
status code. A 404 response does NOT match a `2xx` entry.


---

```yaml
scenario: S-001-87
name: status-class-routing
description: >
  Two response entries on the same path: one with status "2xx", one with "4xx".
  A 200 response matches the 2xx entry. A 404 response matches the 4xx entry.
  Neither matches the other's class.
tags: [status, routing, profile, adr-0036]
refs: [FR-001-15, ADR-0036]

# This is a profile-level matching test, not a transform test.
# The test configures two entries and asserts correct spec selection.

profile_entries:
  - spec: success-rewrite
    direction: response
    match:
      path: "/api/**"
      status: "2xx"
  - spec: error-rewrite
    direction: response
    match:
      path: "/api/**"
      status: "4xx"

assertions:
  - status_code: 200
    request_path: "/api/test"
    expected_spec: success-rewrite
  - status_code: 404
    request_path: "/api/test"
    expected_spec: error-rewrite
  - status_code: 301
    request_path: "/api/test"
    expected_spec: null  # no match → passthrough
```

---

### S-001-88: Status Exact Match — 404 Fires, 200 Doesn't

Exact status match: only a 404 response triggers the spec.


---

```yaml
scenario: S-001-88
name: status-exact-match
description: >
  Response entry with status: 404 (exact). A 404 response matches.
  A 200 response does NOT match.
tags: [status, routing, exact, adr-0036]
refs: [FR-001-15, ADR-0036]

profile_entries:
  - spec: not-found-handler
    direction: response
    match:
      path: "/api/**"
      status: 404

assertions:
  - status_code: 404
    request_path: "/api/test"
    expected_spec: not-found-handler
  - status_code: 200
    request_path: "/api/test"
    expected_spec: null
```

---

### S-001-89: Status Range — 400-499 Matches

Range-based status matching: `status: "400-499"` matches any 4xx code.


---

```yaml
scenario: S-001-89
name: status-range-match
description: >
  Response entry with status: "400-499" (range). 404 matches. 500 doesn't.
tags: [status, routing, range, adr-0036]
refs: [FR-001-15, ADR-0036]

profile_entries:
  - spec: client-error-handler
    direction: response
    match:
      path: "/api/**"
      status: "400-499"

assertions:
  - status_code: 404
    request_path: "/api/test"
    expected_spec: client-error-handler
  - status_code: 400
    request_path: "/api/test"
    expected_spec: client-error-handler
  - status_code: 499
    request_path: "/api/test"
    expected_spec: client-error-handler
  - status_code: 500
    request_path: "/api/test"
    expected_spec: null
```

---

### S-001-90: Status on Request Direction → Load-Time Error

`match.status` on a request-direction entry is invalid and MUST be rejected
at load time.


---

```yaml
scenario: S-001-90
name: status-on-request-direction-rejected
description: >
  A profile entry with direction: request and match.status MUST be rejected
  at load time with a diagnostic. Status matching is only valid for responses.
tags: [status, validation, load-time, adr-0036]
refs: [FR-001-15, ADR-0036]

profile_entries:
  - spec: some-spec
    direction: request
    match:
      path: "/api/**"
      status: "4xx"

expected_error:
  type: ProfileResolveException
  message_contains:
    - "status"
    - "response"
```

---

### S-001-91: Status Negation — !5xx Matches 200, Doesn't Match 502

Negation prefix `!` inverts the pattern.


---

```yaml
scenario: S-001-91
name: status-negation
description: >
  Response entry with status: "!5xx" matches any code NOT in the 5xx class.
  200 matches. 502 doesn't.
tags: [status, routing, negation, adr-0036]
refs: [FR-001-15, ADR-0036]

profile_entries:
  - spec: non-server-error
    direction: response
    match:
      path: "/api/**"
      status: "!5xx"

assertions:
  - status_code: 200
    request_path: "/api/test"
    expected_spec: non-server-error
  - status_code: 404
    request_path: "/api/test"
    expected_spec: non-server-error
  - status_code: 502
    request_path: "/api/test"
    expected_spec: null
```

---

### S-001-92: Status Specificity — Exact Beats Class

When two entries both match (e.g., `4xx` and `404` both match a 404 response),
the more specific entry wins per ADR-0006 weighted constraint scoring.


---

```yaml
scenario: S-001-92
name: status-exact-beats-class-specificity
description: >
  Two response entries on the same path: one with status "4xx" (class, weight 1),
  one with status 404 (exact, weight 2). A 404 response matches both, but the
  exact entry wins because it has higher constraint count.
tags: [status, specificity, adr-0006, adr-0036]
refs: [FR-001-15, ADR-0006, ADR-0036]

profile_entries:
  - spec: generic-error
    direction: response
    match:
      path: "/api/**"
      status: "4xx"
  - spec: not-found-specific
    direction: response
    match:
      path: "/api/**"
      status: 404

assertions:
  - status_code: 404
    request_path: "/api/test"
    expected_spec: not-found-specific  # exact (weight 2) beats class (weight 1)
  - status_code: 403
    request_path: "/api/test"
    expected_spec: generic-error  # only class matches
```

---

### S-001-93: Body When Predicate Matches → Spec Fires (Phase 2)

Placeholder — Phase 2 (`match.when`). Body predicate evaluates to truthy → entry selected.


---

```yaml
scenario: S-001-93
name: when-predicate-matches
description: >
  Response entry with match.when predicate. Body field matches the predicate
  expression → entry selected.
tags: [when, predicate, routing, adr-0036, phase-2]
refs: [FR-001-16, ADR-0036]
status: planned
```

---

### S-001-94: Body When Predicate Fails → No Match (Fail-Safe) (Phase 2)

Placeholder — Phase 2. When predicate evaluation throws → treat as non-matching (fail-safe).


---

```yaml
scenario: S-001-94
name: when-predicate-error-fail-safe
description: >
  When predicate expression throws during evaluation. Entry is treated as
  non-matching (fail-safe). Warning logged.
tags: [when, error, fail-safe, adr-0036, phase-2]
refs: [FR-001-16, ADR-0036]
status: planned
```

---

### S-001-95: Combined Status + When — 200 + Admin → Admin Spec (Phase 2)

Placeholder — Phase 2. Combined status and body predicate matching.


---

```yaml
scenario: S-001-95
name: status-plus-when-combined
description: >
  Two response entries: both status "2xx" but different when predicates.
  200 + role=admin → admin spec. 200 + role=user → user spec.
tags: [status, when, combined, adr-0036, phase-2]
refs: [FR-001-15, FR-001-16, ADR-0036]
status: planned
```

---

### S-001-96: No Match on Status + No Match on When → Passthrough (Phase 2)

Placeholder — Phase 2. Neither status nor body predicate match → passthrough.


---

```yaml
scenario: S-001-96
name: status-when-no-match-passthrough
description: >
  Response status doesn't match any entry's status pattern, and body doesn't
  match any entry's when predicate. Result is passthrough.
tags: [status, when, passthrough, adr-0036, phase-2]
refs: [FR-001-15, FR-001-16, ADR-0036]
status: planned
```

---

### S-001-97: Non-JSON Body with When Predicate → Graceful Skip (Phase 2)

Placeholder — Phase 2. Non-JSON body with `match.when` → entry skipped (fail-safe).


---

```yaml
scenario: S-001-97
name: when-non-json-body-skip
description: >
  When predicate configured but body is not JSON (e.g., HTML). Entry is
  skipped gracefully. Debug-level log.
tags: [when, non-json, fail-safe, adr-0036, phase-2]
refs: [FR-001-16, ADR-0036]
status: planned
```

---

### S-001-98: When Predicate on Request Direction → Works (Phase 2)

Placeholder — Phase 2. Unlike `match.status`, `match.when` is valid on
both request and response directions.


---

```yaml
scenario: S-001-98
name: when-predicate-request-direction
description: >
  When predicate on request-direction entry. Unlike status (response-only),
  when predicates work on both directions.
tags: [when, request, both-directions, adr-0036, phase-2]
refs: [FR-001-16, ADR-0036]
status: planned
```

---

### S-001-99: When + Pipeline — Two Non-Exclusive Predicates → ADR-0012 Chain (Phase 2)

Placeholder — Phase 2. Two entries with overlapping `when` predicates both
match → pipeline fires per ADR-0012.


---

```yaml
scenario: S-001-99
name: when-pipeline-chaining
description: >
  Two entries with non-exclusive when predicates both match the same message.
  Both specs fire in pipeline order per ADR-0012.
tags: [when, pipeline, chaining, adr-0012, adr-0036, phase-2]
refs: [FR-001-16, ADR-0012, ADR-0036]
status: planned
```

---

### S-001-100: Status Unquoted Integer YAML Parsing

YAML `status: 404` (unquoted) produces an IntNode, not a string. The parser
MUST handle this correctly — same behavior as `status: "404"`.


---

```yaml
scenario: S-001-100
name: status-unquoted-integer-yaml
description: >
  YAML unquoted integer (status: 404) produces IntNode in the parser.
  The type-aware parseStatusField() handles this correctly, producing the
  same StatusPattern.Exact(404) as the quoted string "404".
tags: [status, parser, yaml, adr-0036]
refs: [FR-001-15, ADR-0036]

# This is a parser test, not a matching test.
# Verified in StatusPatternParserTest.

profile_entry_yaml: |
  spec: some-spec
  direction: response
  match:
    path: "/api/**"
    status: 404

assertions:
  - parsed_status_pattern: Exact(404)
  - equivalent_to: 'status: "404"'
```

---

## Scenario Index

| ID | Name | Category | Tags |
|----|------|----------|------|
| S-001-01 | pingam-callback-username-password | PingAM | pingam, callback, structural, core |
| S-001-02 | pingam-callback-reverse | PingAM | pingam, callback, reverse, bidirectional |
| S-001-03 | pingam-success-strip-internals | PingAM | pingam, success, remove-fields |
| S-001-04 | pingam-callback-choice-confirmation | PingAM | pingam, callback, choice, structural |
| S-001-05 | pingam-no-callbacks-simple-success | PingAM | pingam, edge-case, conditional |
| S-001-06 | strip-internal-fields | Gateway | gateway, remove, security, kong-pattern |
| S-001-07 | strip-internal-fields-passthrough | Gateway | gateway, remove, open-world, passthrough |
| S-001-08 | rename-fields-api-versioning | Gateway | gateway, rename, api-versioning, kong-pattern |
| S-001-09 | add-default-values | Gateway | gateway, defaults, kong-pattern |
| S-001-10 | add-gateway-metadata | Gateway | gateway, enrichment, metadata |
| S-001-11 | flatten-nested-object | Structural | structural, flatten, deep-path |
| S-001-12 | nest-flat-object | Structural | structural, nesting, reverse |
| S-001-13 | reshape-array-of-objects | Structural | structural, array, scim |
| S-001-14 | oauth-token-normalization | Structural | structural, oauth, conditional |
| S-001-15 | conditional-error-vs-success | Conditional | conditional, error-handling, structural |
| S-001-16 | conditional-success-path | Conditional | conditional, structural |
| S-001-17 | value-mapping-enum-translation | Conditional | conditional, value-mapping, enum |
| S-001-18 | passthrough-no-match | Edge Cases | passthrough, edge-case, safety |
| S-001-19 | passthrough-invalid-json | Edge Cases | passthrough, edge-case, safety |
| S-001-20 | open-world-extra-fields | Edge Cases | open-world, passthrough, forward-compat |
| S-001-21 | empty-input-object | Edge Cases | edge-case, empty, robustness |
| S-001-22 | null-values-in-input | Edge Cases | edge-case, null, robustness |
| S-001-23 | large-array-performance | Edge Cases | performance, array, large |
| S-001-24 | evaluation-error-returns-error-response | Edge Cases | error, error-response, safety, adr-0022 |
| S-001-25 | multi-engine-rename-jslt | Multi-Engine | multi-engine, jslt, rename |
| S-001-26 | multi-engine-rename-jolt | Multi-Engine | multi-engine, jolt, rename |
| S-001-27 | multi-engine-rename-jq | Multi-Engine | multi-engine, jq, rename |
| S-001-28 | unknown-engine-rejected | Multi-Engine | multi-engine, error, validation |
| S-001-29 | bidirectional-roundtrip-pingam | Bidirectional | bidirectional, round-trip, pingam |
| S-001-30 | bidirectional-flat-nested | Bidirectional | bidirectional, structural, round-trip |
| S-001-31 | error-normalize-rfc9457 | Error Normalization | error-normalization, rfc9457 |
| S-001-32 | pingam-error-normalization | Error Normalization | pingam, error-normalization |
| S-001-33 | header-to-body-injection | Header ↔ Body | header, body, injection, adr-0002 |
| S-001-34 | body-to-header-injection | Header ↔ Body | header, body, injection, dynamic, adr-0002 |
| S-001-35 | missing-header-returns-null | Header ↔ Body | header, edge-case, null, adr-0002 |
| S-001-36 | conditional-status-error-body | Status Code | status, conditional, error, adr-0003 |
| S-001-37 | status-in-body-expression | Status Code | status, body, variable, adr-0003 |
| S-001-38 | unconditional-status-set | Status Code | status, unconditional, adr-0003 |
| S-001-38i | status-when-predicate-error-keep | Status Code | status, error-handling, when-predicate |
| S-001-38a | url-path-rewrite-dispatch | URL Rewriting | url, path-rewrite, de-polymorphize, adr-0027 |
| S-001-38b | url-query-param-operations | URL Rewriting | url, query, add, remove, glob, adr-0027 |
| S-001-38c | url-method-override-conditional | URL Rewriting | url, method, override, conditional, adr-0027 |
| S-001-38d | url-path-expr-returns-null | URL Rewriting | url, error, null, validation |
| S-001-38e | url-method-invalid-rejected | URL Rewriting | url, method, validation, load-time |
| S-001-38f | url-block-on-response-ignored | URL Rewriting | url, direction, response, warning |
| S-001-38g | url-to-body-extraction | URL Rewriting | url, body, extraction, path-segment, query-param, adr-0027, adr-0021 |
| S-001-39 | jolt-unsupported-predicate-rejected | Engine Capability | engine, capability, jolt, validation, adr-0004 |
| S-001-40 | jolt-unsupported-headers-rejected | Engine Capability | engine, capability, jolt, validation, adr-0004 |
| S-001-41 | concurrent-spec-versions | Version Pinning | version, profile, concurrent, adr-0005 |
| S-001-42 | missing-spec-version-rejected | Version Pinning | version, profile, validation, adr-0005 |
| S-001-43 | bare-spec-resolves-to-latest | Version Pinning | version, profile, latest, adr-0005 |
| S-001-44 | specific-path-beats-wildcard | Match Resolution | profile, match, specificity, adr-0006 |
| S-001-45 | ambiguous-tie-rejected | Match Resolution | profile, match, ambiguous, validation, adr-0006 |
| S-001-46 | constraint-count-tiebreaker | Match Resolution | profile, match, tiebreaker, adr-0006 |
| S-001-47 | telemetry-listener-lifecycle | Observability | observability, telemetry, spi, adr-0007 |
| S-001-48 | trace-context-propagation | Observability | observability, tracing, correlation, adr-0007 |
| S-001-49 | profile-chain-jolt-then-jslt | Profile Chaining | profile-chaining, mixed-engine, jolt, jslt, adr-0008 |
| S-001-50 | apply-directive-mapper-pipeline | Reusable Mappers | mappers, mapperRef, apply, resolution, fr-001-08, adr-0014 |
| S-001-51 | mapper-ref-missing-rejected | Reusable Mappers | mappers, mapperRef, apply, validation, error, fr-001-08, adr-0014 |
| S-001-52 | mapper-ref-duplicate-rejected | Reusable Mappers | mappers, mapperRef, duplicate, validation, error, fr-001-08, adr-0014 |
| S-001-53 | schema-valid-load-time | Schema Validation | schema, validation, load-time, fr-001-09, adr-0001 |
| S-001-54 | schema-invalid-rejected | Schema Validation | schema, validation, error, load-time, fr-001-09, adr-0001 |
| S-001-55 | schema-strict-mode-runtime-failure | Schema Validation | schema, validation, strict-mode, runtime, fr-001-09, adr-0001 |
| S-001-56 | pipeline-chain-abort-on-failure | Pipeline Chaining & Message Semantics | profile-chaining, abort, error, adr-0012, adr-0013 |
| S-001-57 | transform-context-headers-in-body | Pipeline Chaining & Message Semantics | transform-context, headers, body, do-001-07 |
| S-001-58 | copy-on-wrap-abort-error-response | Pipeline | copy-on-wrap, abort, error-response, adr-0013, adr-0022 |
| S-001-59 | apply-directive-missing-expr-rejected | Reusable Mappers | mappers, apply, validation, error, fr-001-08, adr-0014 |
| S-001-60 | direction-agnostic-both-bindings | Direction Semantics | direction, agnostic, profile, adr-0016 |
| S-001-61 | status-null-in-request-transform | Status Code | status, null, request, direction, adr-0017 |
| S-001-62 | sensitive-path-invalid-syntax-rejected | Sensitive Fields | sensitive, validation, error, adr-0019, nfr-001-06 |
| S-001-75 | sensitive-paths-valid-parsed | Sensitive Fields | sensitive, validation, load-time, adr-0019, nfr-001-06 |
| S-001-63 | nullable-status-integer-contract | Status Code | status, null, nullable, integer, adr-0020 |
| S-001-64 | query-params-in-body-expression | TransformContext | query-params, context, branching, adr-0021 |
| S-001-65 | cookies-in-body-expression | TransformContext | cookies, context, adr-0021 |
| S-001-66 | load-time-error-type-discrimination | Error Type Catalogue | error-catalogue, load-time, exception-hierarchy, adr-0024 |
| S-001-67 | eval-budget-exceeded-exception-type | Error Type Catalogue | error-catalogue, eval-time, budget, exception-hierarchy, adr-0024 |
| S-001-68 | spec-parse-exception-source-path | Error Type Catalogue | error-catalogue, load-time, source-path, adr-0024 |
| S-001-69 | headers-all-set-cookie-multi-value | Multi-Value Headers | headers, multi-value, headers-all, set-cookie, adr-0026 |
| S-001-70 | headers-all-missing-returns-null | Multi-Value Headers | headers, multi-value, headers-all, null, edge-case, adr-0026 |
| S-001-71 | headers-all-x-forwarded-for-chain | Multi-Value Headers | headers, multi-value, headers-all, x-forwarded-for, adr-0026 |
| S-001-72 | header-case-normalization | Header Transforms | headers, case-insensitive, normalization, rfc9110 |
| S-001-73 | chain-direction-conflict-rejected | Transform Profiles | profile, chain, direction, validation, error, load-time |
| S-001-74 | chain-step-structured-logging | Chain Step Logging | chain-step-logging, structured-logging, nfr-001-08, profile-chaining |
| S-001-76 | reload-swaps-registry-atomically | Hot Reload | hot-reload, atomic-swap, registry, nfr-001-05 |
| S-001-77 | fail-safe-reload-preserves-old | Hot Reload | hot-reload, fail-safe, error, registry, nfr-001-05 |
| S-001-78 | concurrent-reads-consistent-snapshot | Hot Reload | hot-reload, concurrent, thread-safety, atomic-swap, nfr-001-05 |
| S-001-79 | benchmark-identity-1kb | Performance Benchmarks | benchmark, performance, identity, nfr-001-03, adr-0028 |
| S-001-80 | benchmark-field-mapping-10kb | Performance Benchmarks | benchmark, performance, field-mapping, nfr-001-03, adr-0028 |
| S-001-81 | benchmark-complex-50kb | Performance Benchmarks | benchmark, performance, complex, array, nfr-001-03, adr-0028 |
| S-001-82 | session-inject-subject | Session Context | session, subject, request, fr-001-13, adr-0030 |
| S-001-83 | session-conditional-roles | Session Context | session, roles, conditional, response, fr-001-13, adr-0030 |
| S-001-84 | session-null-safe-access | Session Context | session, null, null-safe, fr-001-13, adr-0030 |
| S-001-85 | session-jolt-rejected-at-load-time | Session Context | session, jolt, validation, load-time, fr-001-13, adr-0030 |
| S-001-86 | unknown-key-in-spec-rejected | Edge Cases | error, spec-parse, strict-validation, fail-fast |
| S-001-87 | status-class-routing | CRR | status, routing, profile, adr-0036 |
| S-001-88 | status-exact-match | CRR | status, routing, exact, adr-0036 |
| S-001-89 | status-range-match | CRR | status, routing, range, adr-0036 |
| S-001-90 | status-on-request-direction-rejected | CRR | status, validation, load-time, adr-0036 |
| S-001-91 | status-negation | CRR | status, routing, negation, adr-0036 |
| S-001-92 | status-exact-beats-class-specificity | CRR | status, specificity, adr-0006, adr-0036 |
| S-001-93 | when-predicate-matches | CRR | when, predicate, routing, adr-0036, phase-2 |
| S-001-94 | when-predicate-error-fail-safe | CRR | when, error, fail-safe, adr-0036, phase-2 |
| S-001-95 | status-plus-when-combined | CRR | status, when, combined, adr-0036, phase-2 |
| S-001-96 | status-when-no-match-passthrough | CRR | status, when, passthrough, adr-0036, phase-2 |
| S-001-97 | when-non-json-body-skip | CRR | when, non-json, fail-safe, adr-0036, phase-2 |
| S-001-98 | when-predicate-request-direction | CRR | when, request, both-directions, adr-0036, phase-2 |
| S-001-99 | when-pipeline-chaining | CRR | when, pipeline, chaining, adr-0012, adr-0036, phase-2 |
| S-001-100 | status-unquoted-integer-yaml | CRR | status, parser, yaml, adr-0036 |

## Coverage Matrix

| Spec Requirement | Scenarios |
|------------------|-----------|
| FR-001-01 (Spec Format) | S-001-01 through S-001-17, S-001-49 |
| FR-001-02 (Expression Engine SPI) | S-001-25, S-001-26, S-001-27, S-001-28, S-001-39, S-001-40, S-001-57, S-001-63, S-001-64, S-001-65 |
| FR-001-03 (Bidirectional) | S-001-02, S-001-29, S-001-30, S-001-60 |
| FR-001-04 (Message Envelope) | S-001-19, S-001-21, S-001-22, S-001-58 |
| FR-001-05 (Transform Profiles) | S-001-41, S-001-42, S-001-43, S-001-44, S-001-45, S-001-46, S-001-49, S-001-56, S-001-73 |
| FR-001-06 (Passthrough) | S-001-18, S-001-19 |
| FR-001-07 (Error Handling) | S-001-24, S-001-28, S-001-31, S-001-32, S-001-56, S-001-58, S-001-66, S-001-67, S-001-68 |
| FR-001-08 (Reusable Mappers) | S-001-50, S-001-51, S-001-52, S-001-59 |
| FR-001-09 (Schema Validation) | S-001-53, S-001-54, S-001-55 |
| FR-001-10 (Header Transforms) | S-001-33, S-001-34, S-001-35, S-001-57, S-001-69, S-001-70, S-001-71, S-001-72 |
| FR-001-11 (Status Code Transforms) | S-001-36, S-001-37, S-001-38, S-001-38i, S-001-61, S-001-63 |
| FR-001-12 (URL Rewriting) | S-001-38a, S-001-38b, S-001-38c, S-001-38d, S-001-38e, S-001-38f, S-001-38g |
| FR-001-13 (Session Context Binding) | S-001-82, S-001-83, S-001-84, S-001-85 |
| FR-001-14 (Port Value Objects) | *Verified by dependency analysis and ArchUnit rules, not scenario-testable* |
| FR-001-15 (Status-Code Pattern Matching) | S-001-87, S-001-88, S-001-89, S-001-90, S-001-91, S-001-92, S-001-100 |
| FR-001-16 (Body Predicate Matching) | S-001-93, S-001-94, S-001-95, S-001-96, S-001-97, S-001-98, S-001-99 |
| NFR-001-01 (Stateless) | All — implicit in test harness design |
| NFR-001-03 (Latency <5ms) | S-001-23, S-001-79, S-001-80, S-001-81 |
| NFR-001-04 (Open-world) | S-001-07, S-001-20 |
| NFR-001-07 (Eval budget) | S-001-24 |
| NFR-001-02 (Zero gateway deps) | *Verified by dependency analysis, not scenario-testable* |
| NFR-001-05 (Hot reload) | S-001-76, S-001-77, S-001-78 |
| NFR-001-06 (Sensitive fields) | S-001-62, S-001-75; *static analysis + code review — add more when engine is implemented* |
| NFR-001-08 (Match logging) | S-001-44, S-001-46 (matched profile logged), S-001-74 (chain step logging) |
| NFR-001-09 (Telemetry SPI) | S-001-47 |
| NFR-001-10 (Trace correlation) | S-001-48 |

## Scenario → Test Class Verification

> Updated 2026-02-08 (T-001-51). Each scenario lists the test class(es) that verify it.

| Scenario | Test Class(es) | Notes |
|----------|---------------|-------|
| S-001-01 | `ScenarioSuiteTest`, `TransformEngineTest` | PingAM callback core use case |
| S-001-02 | `ScenarioSuiteTest`, `BidirectionalSpecTest`, `BidirectionalProfileTest` | Reverse transform |
| S-001-03 | `ScenarioSuiteTest` | Success response strip |
| S-001-04 | `ScenarioSuiteTest` | Choice/confirmation callbacks |
| S-001-05 | `ScenarioSuiteTest` | No-callbacks edge case |
| S-001-06 | `ScenarioSuiteTest`, `TransformEngineTest` | Strip internal fields |
| S-001-07 | `JsltExpressionEngineTest` | Open-world `* : .` passthrough (JSLT minus syntax) |
| S-001-08 | `ScenarioSuiteTest`, `TransformEngineTest`, `JsltExpressionEngineTest` | Field renaming |
| S-001-09 | `TransformEngineTest` | Defaults (JSLT absent-field behavior) |
| S-001-10 | — | `now()` dynamic; skipped in suite |
| S-001-11 | `ScenarioSuiteTest`, `TransformEngineTest` | Flatten nested |
| S-001-12 | `ScenarioSuiteTest` | Nest flat |
| S-001-13 | `ScenarioSuiteTest`, `TransformEngineTest`, `JsltExpressionEngineTest` | Array reshape |
| S-001-14 | `ScenarioSuiteTest` | OAuth normalization |
| S-001-15 | `ScenarioSuiteTest`, `JsltExpressionEngineTest` | Conditional error vs success |
| S-001-16 | `ScenarioSuiteTest` | Conditional success path |
| S-001-17 | `ScenarioSuiteTest` | Enum translation |
| S-001-18 | `TransformEngineTest` | Passthrough (no match) |
| S-001-19 | `TransformEngineTest` | Passthrough (invalid JSON) |
| S-001-20 | `ScenarioSuiteTest` | Open-world extra fields |
| S-001-21 | `ScenarioSuiteTest` | Empty input |
| S-001-22 | `ScenarioSuiteTest`, `EvalErrorTest` | Null values |
| S-001-23 | — | Large array; placeholder data |
| S-001-24 | `EvalErrorTest` | JSLT eval error → error response |
| S-001-25 | `ScenarioSuiteTest` | Multi-engine rename (JSLT) |
| S-001-26 | — | JOLT engine not implemented |
| S-001-27 | — | jq engine not implemented |
| S-001-28 | `TransformEngineTest` | Unknown engine rejected |
| S-001-29 | `BidirectionalSpecTest` | Bidirectional round-trip |
| S-001-30 | `BidirectionalSpecTest` | Bidirectional flat ↔ nested |
| S-001-31 | `ScenarioSuiteTest` | RFC 9457 normalization |
| S-001-32 | `ScenarioSuiteTest` | PingAM error normalization |
| S-001-33 | `DynamicHeaderTest` | `$headers` in body |
| S-001-34 | `DynamicHeaderTest` | Body-to-header expr |
| S-001-35 | `DynamicHeaderTest` | Missing header returns null |
| S-001-36 | `StatusTransformTest` | Conditional status change |
| S-001-37 | `StatusTransformTest`, `StatusBindingTest` | `$status` in body |
| S-001-38 | `StatusTransformTest` | Unconditional status set |
| S-001-38a | `UrlPathRewriteTest` | URL path rewrite |
| S-001-38b | `UrlQueryParamTest` | Query param add/remove |
| S-001-38c | `UrlPathRewriteTest` | HTTP method override |
| S-001-38d | `UrlPathRewriteTest` | Path expr null → error |
| S-001-38e | `UrlPathRewriteTest` | Invalid method rejected |
| S-001-38f | `UrlPathRewriteTest` | URL on response ignored |
| S-001-38g | `UrlPathRewriteTest` | URL-to-body extraction |
| S-001-38i | `StatusTransformTest` | Status predicate error → keep |
| S-001-39 | — | JOLT not implemented |
| S-001-40 | — | JOLT not implemented |
| S-001-41 | `TransformEngineTest` | Concurrent spec versions |
| S-001-42 | `TransformEngineTest` | Missing spec version |
| S-001-43 | `TransformEngineTest` | Bare spec → latest |
| S-001-44 | `TransformEngineTest` | Specific path beats wildcard |
| S-001-45 | `TransformEngineTest` | Ambiguous tie rejected |
| S-001-46 | `TransformEngineTest` | Constraint count tie-break |
| S-001-47 | `TelemetryListenerTest` | Telemetry lifecycle events |
| S-001-48 | `TraceContextTest` | Trace context propagation |
| S-001-49 | `ChainingTest` | Profile chain (JOLT not impl) |
| S-001-50 | `MapperPipelineTest` | Mapper + expr pipeline |
| S-001-51 | `MapperPipelineTest` | Missing mapperRef rejected |
| S-001-52 | `MapperPipelineTest` | Duplicate mapperRef rejected |
| S-001-53 | `SchemaValidationTest` | Valid schemas accepted |
| S-001-54 | `SchemaValidationTest` | Invalid schema rejected |
| S-001-55 | `SchemaValidationTest` | Strict-mode runtime failure |
| S-001-56 | `ChainingTest` | Pipeline abort on failure |
| S-001-57 | `DynamicHeaderTest` | `$headers` context binding |
| S-001-58 | `TestAdapterTest` | Copy-on-wrap error path |
| S-001-59 | `MapperPipelineTest` | Apply without expr rejected |
| S-001-60 | `BidirectionalProfileTest` | Direction-agnostic spec |
| S-001-61 | `StatusBindingTest` | `$status` null in request |
| S-001-62 | `SensitiveFieldTest` | Sensitive path validation |
| S-001-63 | `StatusBindingTest` | Nullable status integer |
| S-001-64 | `TransformEngineTest` | Query params in body |
| S-001-65 | `TransformEngineTest` | Cookies in body |
| S-001-66 | `TransformEngineTest` | Load-time error types |
| S-001-67 | `EvalErrorTest` | Eval budget exceeded |
| S-001-68 | `TransformEngineTest` | YAML parse → SpecParseException |
| S-001-69 | `DynamicHeaderTest` | Multi-value Set-Cookie |
| S-001-70 | `DynamicHeaderTest` | `$headers_all` missing → null |
| S-001-71 | `DynamicHeaderTest` | X-Forwarded-For chain |
| S-001-72 | `HeaderNormalizationTest` | Header name case normalization |
| S-001-74 | `ChainingTest` | Chain step logging |
| S-001-75 | `SensitiveFieldTest` | Valid sensitive paths parsed |
| S-001-76 | `HotReloadTest` | Reload swaps registry |
| S-001-77 | `HotReloadTest` | Fail-safe reload preserves old |
| S-001-78 | `HotReloadTest` | Concurrent reads see snapshot |
| S-001-79 | `TransformEngineBenchmark` | Identity 1KB — p95 < 5ms (opt-in) |
| S-001-80 | `TransformEngineBenchmark` | Field-mapping 10KB — p95 < 5ms (opt-in) |
| S-001-81 | `TransformEngineBenchmark` | Complex 50KB — p95 < 5ms (opt-in) |
| S-001-82 | `ScenarioSuiteTest`, `SessionContextBindingTest`, `SessionContextE2ETest` | `$session.sub` in request body (FR-001-13, ADR-0030) |
| S-001-83 | `ScenarioSuiteTest`, `SessionContextBindingTest` | `$session.roles` conditional response (FR-001-13, ADR-0030) |
| S-001-84 | `ScenarioSuiteTest`, `SessionContextBindingTest`, `SessionContextE2ETest` | `$session` null — null-safe access (FR-001-13, ADR-0030) |
| S-001-85 | `SessionContextJoltRejectionTest` | JOLT + `$session` → rejected at load time (FR-001-13, ADR-0030) |
| S-001-86 | `SpecParserTest` | Unknown key in spec → SpecParseException (FR-001-01 strict validation) |
| S-001-87 | `ProfileMatcherTest`, `status-routing.feature` | Status class routing: 2xx matches, 4xx doesn't (FR-001-15, ADR-0036) |
| S-001-88 | `ProfileMatcherTest`, `status-routing.feature` | Status exact match: 404 fires, 200 doesn't (FR-001-15, ADR-0036) |
| S-001-89 | `StatusPatternTest` | Status range: 400-499 matches (FR-001-15, ADR-0036) |
| S-001-90 | `StatusPatternParserTest` | Status on request direction → load-time error (FR-001-15, ADR-0036) |
| S-001-91 | `StatusPatternTest` | Status negation: !5xx matches 200, doesn't match 502 (FR-001-15, ADR-0036) |
| S-001-92 | `ProfileMatcherTest` | Status specificity: exact beats class for same code (FR-001-15, ADR-0006) |
| S-001-93 | `ProfileMatcherTest`, `polymorphic-routing.feature` | Body when predicate matches → spec fires (FR-001-16, ADR-0036) |
| S-001-94 | `ProfileMatcherTest`, `polymorphic-routing.feature` | Body when predicate fails → user spec / fail-safe (FR-001-16, ADR-0036) |
| S-001-95 | `ProfileMatcherTest`, `polymorphic-routing.feature` | Combined status + when routing (FR-001-15, FR-001-16, ADR-0036) |
| S-001-96 | `ProfileMatcherTest` | No match on status + when → passthrough (FR-001-15, FR-001-16) |
| S-001-97 | `ProfileMatcherTest`, `polymorphic-routing.feature` | Non-JSON body with when → graceful skip (FR-001-16) |
| S-001-98 | `ProfileParserTest` | When on request direction — validates compile + match (FR-001-16) |
| S-001-99 | `ProfileMatcherTest` | When + pipeline chaining — mixed entries (FR-001-16, ADR-0012) |
| S-001-100 | `StatusPatternParserTest` | Status unquoted integer YAML parsing (FR-001-15, ADR-0036) |


