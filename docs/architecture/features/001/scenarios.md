# Feature 001 – Scenarios

| Field | Value |
|-------|-------|
| Status | Draft |
| Last updated | 2026-02-07 |
| Linked spec | `docs/architecture/features/001/spec.md` |

> Guardrail: Each scenario is a **testable contract**. The engine MUST produce the
> exact `expected_output` given the `input` and `transform`. Scenarios serve as
> integration test fixtures — they can be loaded directly by parameterized JUnit tests.

---

## Format

Each scenario follows this structure:

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

### S-001-01: PingAM Callback — Username + Password Step

The core use case: transform PingAM's nested callback JSON into a clean, flat
structure suitable for a modern frontend.

```yaml
scenario: S-001-01
name: pingam-callback-username-password
description: >
  Transform PingAM callback response with NameCallback + PasswordCallback
  into a clean JSON structure for frontend consumption.
tags: [pingam, callback, structural, core]

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

### S-001-02: PingAM Callback — Reverse (Frontend → PingAM)

The reverse direction: transform the clean frontend JSON back into PingAM's
callback format, with filled-in values.

```yaml
scenario: S-001-02
name: pingam-callback-reverse
description: >
  Reverse transform: convert clean frontend JSON with filled values back into
  PingAM callback format for submission.
tags: [pingam, callback, reverse, bidirectional]

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

### S-001-03: PingAM Success Response — Strip Internals

Transform PingAM success response to remove internal fields and normalize
the response for the frontend.

```yaml
scenario: S-001-03
name: pingam-success-strip-internals
description: >
  Transform PingAM authentication success response: keep tokenId, normalize
  realm, strip successUrl (internal AM URL not useful for frontend).
tags: [pingam, success, remove-fields]

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

### S-001-04: PingAM Callback — Choice/Confirmation Types

More callback types: ChoiceCallback with multiple options, ConfirmationCallback.

```yaml
scenario: S-001-04
name: pingam-callback-choice-confirmation
description: >
  Transform PingAM callbacks with ChoiceCallback (select from list) and
  ConfirmationCallback (confirm action) into clean frontend fields.
tags: [pingam, callback, choice, structural]

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
      options: null
```

### S-001-05: PingAM — No Callbacks (Simple Login Success)

Edge case: no callbacks at all (zero-page login result).

```yaml
scenario: S-001-05
name: pingam-no-callbacks-simple-success
description: >
  When PingAM returns a success response (no callbacks), the transform
  should correctly detect the non-challenge case.
tags: [pingam, edge-case, conditional]

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
  authId: null
  sessionToken: "AQIC5wM…TU3OQ*"
  realm: "/alpha"
```

---

## Category 2: Gateway Pattern — Field Operations

Use cases derived from Kong/Apigee transformation patterns. These represent
common gateway transformations that message-xform should handle.

### S-001-06: Strip Internal/Debug Fields

Remove fields that should not be exposed to external consumers.

```yaml
scenario: S-001-06
name: strip-internal-fields
description: >
  Remove internal/debug fields from API response before exposing to consumer.
  Common gateway pattern: Kong remove operation equivalent.
tags: [gateway, remove, security, kong-pattern]

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

### S-001-07: Strip Internal Fields with Open-World Passthrough

Same as above, but using JSLT's `* : .` to pass everything through EXCEPT
the blacklisted fields. This tests the open-world assumption.

```yaml
scenario: S-001-07
name: strip-internal-fields-passthrough
description: >
  Remove specific internal fields while passing through ALL other fields
  unchanged. Tests JSLT's object matching pattern (* : .).
tags: [gateway, remove, open-world, passthrough]

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

### S-001-08: Rename Fields (API Versioning)

Rename fields for API version migration without changing structure.

```yaml
scenario: S-001-08
name: rename-fields-api-versioning
description: >
  Rename fields for API version migration. Common gateway pattern:
  v1 used snake_case, v2 uses camelCase. Transform backend v1 to frontend v2.
tags: [gateway, rename, api-versioning, kong-pattern]

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

### S-001-09: Add Default Values

Enrich response with default values for missing fields.

```yaml
scenario: S-001-09
name: add-default-values
description: >
  Add default values for missing fields. Common gateway pattern:
  ensure consumer always gets a complete response shape.
tags: [gateway, defaults, kong-pattern]

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

### S-001-10: Add Gateway Metadata

Inject gateway metadata into response body. Common pattern for traceability.

```yaml
scenario: S-001-10
name: add-gateway-metadata
description: >
  Inject gateway metadata into the response body for traceability.
  Like Kong's add operation but for the body structure.
tags: [gateway, enrichment, metadata]

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

### S-001-11: Flatten Nested Object

Flatten a deeply nested object into a flat key-value structure.

```yaml
scenario: S-001-11
name: flatten-nested-object
description: >
  Flatten a deeply nested user profile into a flat structure for a
  simplified API consumer. Tests deep path access.
tags: [structural, flatten, deep-path]

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

### S-001-12: Nest Flat Object

The reverse of S-001-11: restructure a flat object into a nested hierarchy.

```yaml
scenario: S-001-12
name: nest-flat-object
description: >
  Restructure a flat API response into a nested hierarchy for a richer
  frontend model. Reverse of S-001-11.
tags: [structural, nesting, reverse]

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

### S-001-13: Array-of-Objects Reshaping

Transform an array of objects from one shape into another shape.

```yaml
scenario: S-001-13
name: reshape-array-of-objects
description: >
  Transform SCIM-style user list response into a simplified list API
  format. Tests array iteration with [for ...].
tags: [structural, array, scim]

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

### S-001-14: OAuth Token Response Normalization

Normalize different OAuth token responses into a consistent format.

```yaml
scenario: S-001-14
name: oauth-token-normalization
description: >
  Normalize an OAuth2 token response from a specific IdP into a standard
  format. Tests conditional logic and default values.
tags: [structural, oauth, conditional, normalization]

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

### S-001-15: Conditional Output Shape — Error vs Success

Produce different output structures based on whether the response is an error.

```yaml
scenario: S-001-15
name: conditional-error-vs-success
description: >
  Produce a different output shape depending on whether the upstream response
  indicates success or error. Tests if/else conditional logic.
tags: [conditional, error-handling, structural]

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

### S-001-16: Conditional Output Shape — Success Path

Same transform as S-001-15 but with a success response.

```yaml
scenario: S-001-16
name: conditional-success-path
description: >
  Same transform as S-001-15 but with a success response to verify the
  other branch of the conditional.
tags: [conditional, structural]

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

### S-001-17: Value Mapping (Enum Translation)

Map backend enum values to frontend-friendly labels.

```yaml
scenario: S-001-17
name: value-mapping-enum-translation
description: >
  Map backend status codes to frontend-friendly labels using conditional
  expressions. Common integration pattern.
tags: [conditional, value-mapping, enum]

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

### S-001-18: Passthrough — No Matching Transform

When no transform profile matches, the message passes through unchanged.

```yaml
scenario: S-001-18
name: passthrough-no-match
description: >
  When the input does not match any transform profile, it MUST be passed
  through completely unmodified.
tags: [passthrough, edge-case, safety]

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

### S-001-19: Passthrough — Invalid JSON Body

Non-JSON body should pass through without transformation.

```yaml
scenario: S-001-19
name: passthrough-invalid-json
description: >
  When the message body is not valid JSON, the engine MUST pass through
  unmodified. No transformation, no error.
tags: [passthrough, edge-case, safety]

transform:
  lang: jslt
  expr: |
    { "this": "should never execute" }

input_raw: "<html><body>Not JSON</body></html>"

expected_output_raw: "<html><body>Not JSON</body></html>"
expected_transform_applied: false
```

### S-001-20: Open-World — Extra Fields Preserved

Fields not mentioned in the transform pass through unchanged.

```yaml
scenario: S-001-20
name: open-world-extra-fields
description: >
  When the input contains fields not mentioned in the transform spec,
  those fields MUST be preserved in the output (open-world assumption).
  Tests JSLT's * : . object matching.
tags: [open-world, passthrough, forward-compatibility]

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
  email: "bjensen@example.com"
  custom_field: "should survive"
  _new_field_from_v2: true
```

### S-001-21: Empty Input Object

Transform handles empty input gracefully.

```yaml
scenario: S-001-21
name: empty-input-object
description: >
  Transform handles empty JSON object input without error.
  All derived fields should be null or use defaults.
tags: [edge-case, empty, robustness]

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
  id: null
  name: "Anonymous"
  status: "unknown"
```

### S-001-22: Null Values in Input

Transform correctly handles null values in input fields.

```yaml
scenario: S-001-22
name: null-values-in-input
description: >
  Transform correctly handles null values — they should be passed through
  as null, not dropped or converted to empty strings.
tags: [edge-case, null, robustness]

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
  email: null
  hasEmail: false
```

### S-001-23: Large Array Transformation

Performance scenario: transform a large array of 100 objects.

```yaml
scenario: S-001-23
name: large-array-performance
description: >
  Transform a large array of objects. Tests that the engine handles
  non-trivial array sizes within the latency budget (<5ms).
tags: [performance, array, large]
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

### S-001-24: Strict Mode — JSLT Evaluation Error

In strict mode, a JSLT error should abort the transformation and pass
through the original message.

```yaml
scenario: S-001-24
name: strict-mode-evaluation-error
description: >
  In strict error mode, when a JSLT expression references a function that
  doesn't exist, the engine MUST abort and pass through the original.
tags: [error, strict-mode, safety]
error_mode: strict

transform:
  lang: jslt
  expr: |
    {
      "result": nonexistent-function(.value)
    }

input:
  value: "test"

expected_error:
  type: "expression_compile_error"
  engine: "jslt"
  # Original message passed through unchanged
expected_passthrough: true
```

---

## Category 6: Multi-Engine

Verify that the same logical transformation works across different engines
when they can express the same mapping.

### S-001-25: Multi-Engine — Simple Rename (JSLT)

```yaml
scenario: S-001-25
name: multi-engine-rename-jslt
description: >
  Simple rename transform using JSLT engine.
  Part of multi-engine comparison set with S-001-26, S-001-27.
tags: [multi-engine, jslt, rename]

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

### S-001-26: Multi-Engine — Simple Rename (JOLT)

```yaml
scenario: S-001-26
name: multi-engine-rename-jolt
description: >
  Same rename transform as S-001-25 but using JOLT engine.
  Validates engine SPI interchangeability.
tags: [multi-engine, jolt, rename]

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

### S-001-27: Multi-Engine — Simple Rename (jq)

```yaml
scenario: S-001-27
name: multi-engine-rename-jq
description: >
  Same rename transform as S-001-25 but using jq engine.
  Validates engine SPI interchangeability.
tags: [multi-engine, jq, rename]

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

### S-001-28: Unknown Engine — Rejected at Load Time

```yaml
scenario: S-001-28
name: unknown-engine-rejected
description: >
  When a spec declares an unknown engine, it MUST be rejected at load
  time with a clear error message.
tags: [multi-engine, error, validation]

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

### S-001-29: Bidirectional Round-Trip — PingAM Callbacks

Verify that forward → reverse produces a result that, when forwarded again,
produces the same output as the first forward pass.

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

### S-001-30: Bidirectional — Flat ↔ Nested

```yaml
scenario: S-001-30
name: bidirectional-flat-nested
description: >
  Bidirectional transform between flat API format and nested domain model.
  Forward: flat → nested. Reverse: nested → flat.
tags: [bidirectional, structural, round-trip]

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

### S-001-31: RFC 9457 Problem Details Normalization

```yaml
scenario: S-001-31
name: error-normalize-rfc9457
description: >
  Transform a backend-specific error response into RFC 9457 Problem Details
  format. Common gateway use case for error format standardization.
tags: [error-normalization, rfc9457, structural]

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

### S-001-32: PingAM Error Normalization

```yaml
scenario: S-001-32
name: pingam-error-normalization
description: >
  PingAM returns errors in a non-standard format. Normalize to a consistent
  error envelope for the frontend.
tags: [pingam, error-normalization, structural]

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

### S-001-33: Header-to-Body Injection via `$headers`

Inject a request header value into the transformed response body.

```yaml
scenario: S-001-33
name: header-to-body-injection
description: >
  Inject the X-Request-ID header value into the response body using the
  $headers read-only JSLT variable. Validates ADR-0002 header-to-body bridge.
tags: [header, body, injection, adr-0002]
requires: [FR-001-10]

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

### S-001-34: Body-to-Header Injection via Dynamic `expr`

Extract values from the transformed body and emit them as response headers.

```yaml
scenario: S-001-34
name: body-to-header-injection
description: >
  Extract error code and auth method from the response body and emit them
  as response headers using dynamic expr in the headers.add block.
  Validates ADR-0002 body-to-header bridge.
tags: [header, body, injection, dynamic, adr-0002]
requires: [FR-001-10]

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
      expr: if (.callbacks) "challenge" else "simple"
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

### S-001-35: Missing Header — `$headers` Returns Null

Edge case: referencing a header that doesn't exist returns null.

```yaml
scenario: S-001-35
name: missing-header-returns-null
description: >
  When $headers references a header that was not sent, JSLT returns null.
  The transform should handle this gracefully.
tags: [header, edge-case, null, adr-0002]
requires: [FR-001-10]

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

### S-001-36: Conditional Status Change — Error Body → 400

The key use case: upstream returns 200 but body contains an error → change to 400.

```yaml
scenario: S-001-36
name: conditional-status-error-body
description: >
  Upstream returns 200 with an error in the body. The status block changes
  the response to 400 when the error field is present.
  Validates ADR-0003 conditional when predicate.
tags: [status, conditional, error, adr-0003]
requires: [FR-001-11]

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

### S-001-37: `$status` in Body Expression

Use the original status code inside the JSLT body expression.

```yaml
scenario: S-001-37
name: status-in-body-expression
description: >
  Use $status to include the original HTTP status code in the transformed
  body. Validates ADR-0003 $status read-only variable.
tags: [status, body, variable, adr-0003]
requires: [FR-001-11]

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

### S-001-38: Unconditional Status Set

Set status code unconditionally (no `when` predicate).

```yaml
scenario: S-001-38
name: unconditional-status-set
description: >
  Set the response status code to 202 unconditionally, regardless of body.
  Validates ADR-0003 unconditional status set.
tags: [status, unconditional, adr-0003]
requires: [FR-001-11]

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

## Category 11: Engine Capability Validation

Scenarios validating engine support matrix enforcement (ADR-0004, FR-001-02).

### S-001-39: JOLT Engine with Unsupported Predicate — Rejected at Load Time

A spec using `lang: jolt` with a `when` predicate must be rejected.

```yaml
scenario: S-001-39
name: jolt-unsupported-predicate-rejected
description: >
  A spec declares lang: jolt with a when predicate. Since JOLT does not
  support predicates (per engine support matrix), the engine must reject
  the spec at load time with a clear diagnostic.
  Validates ADR-0004 load-time capability validation.
tags: [engine, capability, jolt, validation, adr-0004]
requires: [FR-001-02]

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

### S-001-40: JOLT Engine with `$headers` Reference — Rejected at Load Time

```yaml
scenario: S-001-40
name: jolt-unsupported-headers-rejected
description: >
  A spec declares lang: jolt and references $headers. Since JOLT does not
  support context variables, the engine must reject at load time.
  Validates ADR-0004 load-time capability validation.
tags: [engine, capability, jolt, validation, adr-0004]
requires: [FR-001-02]

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

## Category 12: Version Pinning

Scenarios validating profile-to-spec version pinning (ADR-0005, FR-001-05).

### S-001-41: Concurrent Spec Versions — Different Routes Use Different Versions

```yaml
scenario: S-001-41
name: concurrent-spec-versions
description: >
  Two profiles reference different versions of the same spec. Both must
  resolve independently at load time. Validates ADR-0005 concurrent versioning.
tags: [version, profile, concurrent, adr-0005]
requires: [FR-001-05]

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

### S-001-42: Missing Spec Version — Rejected at Load Time

```yaml
scenario: S-001-42
name: missing-spec-version-rejected
description: >
  A profile references callback-prettify@3.0.0 but only v1 and v2 are loaded.
  The engine must reject the profile at load time.
  Validates ADR-0005 fail-fast version resolution.
tags: [version, profile, validation, adr-0005]
requires: [FR-001-05]

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

### S-001-43: Bare Spec Reference — Resolves to Latest Version

```yaml
scenario: S-001-43
name: bare-spec-resolves-to-latest
description: >
  A profile references 'callback-prettify' without a version suffix.
  The engine must resolve to the latest loaded version (highest semver).
  Validates ADR-0005 latest-version fallback.
tags: [version, profile, latest, adr-0005]
requires: [FR-001-05]

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

## Category 13: Profile Match Resolution

Scenarios validating most-specific-wins profile matching (ADR-0006, FR-001-05, NFR-001-08).

### S-001-44: Specific Path Beats Wildcard

```yaml
scenario: S-001-44
name: specific-path-beats-wildcard
description: >
  Two profiles match the same request. The profile with more literal path
  segments wins. Validates ADR-0006 specificity scoring.
tags: [profile, match, specificity, adr-0006]
requires: [FR-001-05]

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

### S-001-45: Ambiguous Tie — Rejected at Load Time

```yaml
scenario: S-001-45
name: ambiguous-tie-rejected
description: >
  Two profiles have the same specificity score and same constraint count.
  The engine must reject both at load time as ambiguous.
  Validates ADR-0006 tie-breaking rules.
tags: [profile, match, ambiguous, validation, adr-0006]
requires: [FR-001-05]

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

### S-001-46: Constraint Count Tie-Breaking

```yaml
scenario: S-001-46
name: constraint-count-tiebreaker
description: >
  Two profiles have the same specificity score but different constraint counts.
  The profile with more constraints (method, content-type) wins.
  Validates ADR-0006 tie-breaking by constraint count.
tags: [profile, match, tiebreaker, adr-0006]
requires: [FR-001-05]

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

## Category 14: Observability

Scenarios validating the telemetry SPI and trace correlation (ADR-0007, NFR-001-09, NFR-001-10).

### S-001-47: TelemetryListener Receives Transform Lifecycle Events

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
requires: [NFR-001-09]

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

### S-001-48: Trace Context Propagation — X-Request-ID in Log Output

```yaml
scenario: S-001-48
name: trace-context-propagation
description: >
  The engine propagates incoming X-Request-ID through all structured
  log entries and telemetry events. No new traces are created.
  Validates NFR-001-10 trace correlation.
tags: [observability, tracing, correlation, adr-0007]
requires: [NFR-001-10]

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
| S-001-24 | strict-mode-evaluation-error | Edge Cases | error, strict-mode, safety |
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

## Coverage Matrix

| Spec Requirement | Scenarios |
|------------------|-----------|
| FR-001-01 (Spec Format) | S-001-01 through S-001-17 |
| FR-001-02 (Expression Engine SPI) | S-001-25, S-001-26, S-001-27, S-001-28, S-001-39, S-001-40 |
| FR-001-03 (Bidirectional) | S-001-02, S-001-29, S-001-30 |
| FR-001-04 (Message Envelope) | S-001-19 |
| FR-001-05 (Transform Profiles) | S-001-41, S-001-42, S-001-43, S-001-44, S-001-45, S-001-46 |
| FR-001-06 (Passthrough) | S-001-18, S-001-19 |
| FR-001-07 (Error Handling) | S-001-24, S-001-28 |
| FR-001-08 (Reusable Mappers) | *Not yet covered — add when mapperRef is specified* |
| FR-001-09 (Schema Validation) | *Not yet covered — add input/output schema test vectors* |
| FR-001-10 (Header Transforms) | S-001-33, S-001-34, S-001-35 |
| FR-001-11 (Status Code Transforms) | S-001-36, S-001-37, S-001-38 |
| NFR-001-01 (Stateless) | All — implicit in test harness design |
| NFR-001-03 (Latency <5ms) | S-001-23 |
| NFR-001-04 (Open-world) | S-001-07, S-001-20 |
| NFR-001-07 (Eval budget) | S-001-24 |
| NFR-001-08 (Match logging) | S-001-44, S-001-46 (matched profile logged) |
| NFR-001-09 (Telemetry SPI) | S-001-47 |
| NFR-001-10 (Trace correlation) | S-001-48 |
