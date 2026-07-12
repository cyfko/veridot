---
title: "Chapter 5: Who Can Sign? — Capabilities"
description: "Control which services can sign tokens for which scopes using cryptographic capability grants."
sidebar_position: 5
pagination_prev: learn/going-distributed
pagination_next: learn/session-management
---

# Chapter 5: Who Can Sign? — Capabilities

It's not enough to know that a token was signed by `CN@hash(pk)` belonging to the `shipping-service`. What if the `shipping-service` tries to sign a token for the `group:orders` scope?

Veridot Protocol V5 enforces **Structural Authorization** via **CAPABILITY** entries.

## What is a Capability?

A capability is a signed protocol entry that explicitly authorizes a specific issuer identity to act within a specific scope. 

For instance, at bootstrap, a root trust anchor might issue a capability:
> "Identity `shipping-service@hash123` is authorized to issue configurations and payloads for `site:logistics`."

## Why Structural?

Traditional authorization relies on opaque callback functions, database ACLs, or hardcoded rules. These are inherently brittle and difficult to audit.

By contrast, Veridot's authorization is structural:
1. It is **cryptographically signed** and immutable.
2. It is **independently verifiable** by any node.
3. It creates an **auditable trail**. An auditor can replay the capability entries and perfectly reconstruct the authorization state of the system at any historical moment.

When verifying a NATIVE token (`8:<scope>:<key>`), the Veridot verifier automatically validates that the signer's `CN@hash(pk)` holds a valid, unexpired CAPABILITY for that `<scope>`. If it doesn't, verification strictly fails.
