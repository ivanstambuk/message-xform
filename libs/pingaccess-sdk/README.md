# Local Vendor Libraries

This directory contains vendor SDK JARs that are not available on Maven Central.
JARs are **gitignored** — each developer must obtain them locally.

## Setup

### PingAccess SDK

```bash
# Copy from the docs/reference directory
cp docs/reference/pingaccess-sdk/pingaccess-sdk-9.0.1.0.jar libs/pingaccess-sdk/

# Or extract from a PA Docker image
docker cp $(docker create pingidentity/pingaccess:latest):/opt/server/lib/pingaccess-sdk-9.0.1.0.jar libs/pingaccess-sdk/
```

## Required Files

| Path | Source | Used by |
|------|--------|---------|
| `pingaccess-sdk/pingaccess-sdk-9.0.1.0.jar` | PA Docker image or `docs/reference/` | `adapter-pingaccess` module |
