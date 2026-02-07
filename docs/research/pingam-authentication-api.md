# PingAM Authentication API — Research Notes

> Source: *PingAM 8 Official Documentation* (`docs/reference/pingam-8.pdf`)
> Extracted: 2026-02-07

---

## Overview

PingAM provides the `/json/authenticate` endpoint for authentication and the `/json/sessions` endpoint for session management / logout. The authentication endpoint is **REST-like** (POST only, no CRUDPAQ verbs).

---

## Endpoint: `/json/authenticate`

**Method:** `POST`

**URL pattern:**
```
https://{host}/am/json/realms/root/realms/{realm}/authenticate
```

Realm hierarchy must be specified in full, with each level prefixed by `realms/`:
```
/realms/root/realms/customers/realms/europe
```

### Query Parameters

| Parameter | Description |
|-----------|-------------|
| `authIndexType` | Type of auth: `service`, `resource`, `composite_advice`, `transaction` |
| `authIndexValue` | Value for the auth type (e.g., tree name) |
| `noSession` | When `true`, AM doesn't return a session token |

### Required Headers

| Header | Value |
|--------|-------|
| `Content-Type` | `application/json` |
| `Accept-API-Version` | `resource=2.0, protocol=1.0` |

---

## Authentication Flow: Simple (Zero-Page Login)

Username and password are sent **in headers**, with an **empty body**:

```bash
curl --request POST \
  --header "Content-Type: application/json" \
  --header "X-OpenAM-Username: bjensen" \
  --header "X-OpenAM-Password: Ch4ng31t" \
  --header "Accept-API-Version: resource=2.0, protocol=1.0" \
  'https://am.example.com:8443/am/json/realms/root/realms/alpha/authenticate'
```

### Success Response

```json
{
  "tokenId": "AQIC5wM…TU3OQ*",
  "successUrl": "/am/console",
  "realm": "/alpha"
}
```

### Success Response (No Session)

When `noSession=true`:
```json
{
  "message": "Authentication Successful",
  "successUrl": "/am/console",
  "realm": "/"
}
```

### Success Response (HttpOnly Cookies enabled)

```json
{
  "tokenId": "",
  "successUrl": "/am/console",
  "realm": "/alpha"
}
```

---

## Authentication Flow: Callback-Based (Complex Journeys)

For complex authentication (MFA, step-up, etc.), AM uses a **callback mechanism**. The flow is:

1. Client sends initial POST (empty body, or with `authIndexType`/`authIndexValue`)
2. AM responds with `authId` + `callbacks` array
3. Client fills in callback input values and POSTs the entire JSON back
4. AM responds with either more callbacks or success

### Step 1: AM Returns Callbacks

```json
{
  "authId": "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJvdGsiOiJ…",
  "template": "",
  "stage": "DataStore1",
  "callbacks": [
    {
      "type": "NameCallback",
      "output": [
        {
          "name": "prompt",
          "value": " User Name: "
        }
      ],
      "input": [
        {
          "name": "IDToken1",
          "value": ""
        }
      ]
    },
    {
      "type": "PasswordCallback",
      "output": [
        {
          "name": "prompt",
          "value": " Password: "
        }
      ],
      "input": [
        {
          "name": "IDToken2",
          "value": ""
        }
      ]
    }
  ]
}
```

**Key fields:**
- `authId` — JWT identifying the auth context. Must be returned in every callback response.
- `template` — UI template hint (may be empty)
- `stage` — Current node stage name (e.g., `"DataStore1"`)
- `callbacks[]` — Array of callback objects
  - `type` — Callback class name (e.g., `NameCallback`)
  - `output[]` — Read-only info for display (`name`/`value` pairs)
  - `input[]` — Fields client must fill in (`name`/`value` pairs, value initially empty)

### Step 2: Client Returns Filled Callbacks

POST the entire JSON back with input values filled:

```json
{
  "authId": "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJvdGsiOiJ…",
  "template": "",
  "stage": "DataStore1",
  "callbacks": [
    {
      "type": "NameCallback",
      "output": [
        {
          "name": "prompt",
          "value": " User Name: "
        }
      ],
      "input": [
        {
          "name": "IDToken1",
          "value": "bjensen"
        }
      ]
    },
    {
      "type": "PasswordCallback",
      "output": [
        {
          "name": "prompt",
          "value": " Password: "
        }
      ],
      "input": [
        {
          "name": "IDToken2",
          "value": "Ch4ng31t"
        }
      ]
    }
  ]
}
```

### Step 3: Success (or more callbacks)

On success:
```json
{
  "tokenId": "AQIC5wM…TU3OQ*",
  "successUrl": "/am/console",
  "realm": "/alpha"
}
```

On complex journeys, AM may return **additional callbacks** (e.g., MFA, device verification). Each must be completed sequentially.

---

## Callback Types

### Category: Interactive (request information)

| Callback Type | Purpose |
|--------------|---------|
| `BooleanAttributeInputCallback` | Boolean yes/no confirmation |
| `ChoiceCallback` | Select from a list of options |
| `ConfirmationCallback` | Confirm an action |
| `ConsentMappingCallback` | Collect consent for data sharing |
| `DeviceBindingCallback` | Bind a device to user account |
| `DeviceProfileCallback` | Collect device profile data |
| `DeviceSigningVerifierCallback` | Verify device signature |
| `HiddenValueCallback` | Carry hidden values |
| `IdPCallback` | Social IdP selection data |
| `KbaCreateCallback` | Create knowledge-based auth questions |
| `NameCallback` | **Username / text input** |
| `NumberAttributeInputCallback` | Numeric attribute input |
| `PasswordCallback` | **Password input** |
| `PingOneProtectEvaluationCallback` | PingOne risk evaluation signals |
| `PingOneProtectInitializeCallback` | Initialize PingOne Signals SDK |
| `SelectIdPCallback` | Choose social identity provider |
| `StringAttributeInputCallback` | String attribute input (with IDM validation) |
| `TermsAndConditionsCallback` | Accept terms |
| `TextInputCallback` | Generic text input |
| `ValidatedPasswordCallback` | Password with IDM policy validation |
| `ValidatedUsernameCallback` | Username with IDM policy validation |

### Category: Read-only (return information)

| Callback Type | Purpose |
|--------------|---------|
| `MetadataCallback` | Arbitrary JSON metadata |
| `PollingWaitCallback` | Wait interval before retry |
| `RedirectCallback` | Redirect to external URL |
| `SuspendedTextOutputCallback` | Suspended journey text |
| `TextOutputCallback` | Display text message |

### Category: Backchannel (access request data)

| Callback Type | Purpose |
|--------------|---------|
| `HttpCallback` | Access HTTP request headers/parameters |

---

## Canonical Callback JSON Structure

Every callback follows this pattern:

```json
{
  "type": "<CallbackClassName>",
  "output": [
    {
      "name": "<fieldName>",
      "value": "<displayValue>"
    }
  ],
  "input": [
    {
      "name": "IDToken<N>",
      "value": ""
    }
  ]
}
```

**Naming convention for input fields:**
- `IDToken1`, `IDToken2`, etc. — Sequentially numbered within the callback array
- Some callbacks add suffixes: `IDToken1validateOnly`, `IDToken1question`, `IDToken1answer`

---

## Session Token Lifecycle

### After successful authentication

AM returns a `tokenId` — the **session token** (also called SSO token):
- **Server-side sessions**: ~100 bytes reference to CTS token store
- **Client-side sessions**: 2000+ bytes (contains session state)

### Using the session token

Pass the token in subsequent API calls via the **session cookie header**:

```bash
curl --request POST \
  --header "Content-Type: application/json" \
  --header "iPlanetDirectoryPro: AQIC5w…NTcy*" \
  --header "Accept-API-Version: resource=2.0, protocol=1.0" \
  ...
```

**Cookie header name:** `iPlanetDirectoryPro` (default, configurable in AM admin UI)

### Logout

```bash
curl --request POST \
  --header "Content-type: application/json" \
  --header "iPlanetDirectoryPro: AQICS…NzEz*" \
  --header "Accept-API-Version: resource=3.1, protocol=1.0" \
  'https://am.example.com:8443/am/json/realms/root/realms/alpha/sessions/?_action=logout'
```

Response:
```json
{
  "result": "Successfully logged out"
}
```

---

## UTF-8 Support

Usernames/passwords with non-ASCII characters must be base64-encoded per RFC 2047:

```
=?UTF-8?B?yZfDq8mxw7g=?=
```

Passed via `X-OpenAM-Username` / `X-OpenAM-Password` headers.

---

## Backchannel Authentication

AM also supports `/json/authenticate/backchannel` for third-party federation:

### Initialize
```bash
POST /json/realms/root/realms/alpha/authenticate/backchannel/initialize
Authorization: Bearer {oauth2_token_with_back_channel_authentication_scope}

{
  "type": "service",
  "value": "Login",
  "subject": {
    "type": "user",
    "name": "bjensen"
  },
  "trackingId": "Y5tyzQi9cGVJjy2L"
}
```

Response:
```json
{
  "transaction": "b3070138-cd73-4ef2-bd58-812602d7b757",
  "redirectUri": "https://am.example.com:8443/am/UI/Login?realm=/alpha&authIndexType=transaction&authIndexValue=b3070138-cd73-4ef2-bd58-812602d7b757"
}
```

---

## Implications for message-xform

### Primary Transformation Targets

1. **Callback response restructuring** — Transform between PingAM's callback format and downstream/upstream API formats
2. **Header ↔ payload promotion** — `X-OpenAM-Username`/`X-OpenAM-Password` headers to/from JSON body fields
3. **Session token handling** — Extract `tokenId` from response and promote to `iPlanetDirectoryPro` header for subsequent calls
4. **Error format normalization** — Transform PingAM error responses to RFC 9457 Problem Details

### Key Design Observations

- The callback JSON is **deeply nested** and **variable** — different callback types have different output/input field names
- The `authId` JWT must be preserved across the callback round-trip (it's the stateful context identifier)
- Input field names follow a convention (`IDToken1`, `IDToken2`) but some callbacks add suffixes
- The flow is **multi-step**: transformations may need to handle state across multiple request/response pairs
- **Both request and response bodies must be transformable** — the same JSON structure flows in both directions

---

*Status: COMPLETE — Full authentication REST API documented from official source*
