# Security Policy

## Supported Versions

Yukta is in alpha. Only the latest release receives security fixes; there are no backported
patches to older versions.

| Version                | Supported          |
| ---------------------- | ------------------ |
| Latest release (0.1.x) | :white_check_mark: |
| Anything older         | :x:                |

## Reporting a Vulnerability

We take the security of Yukta seriously. If you believe you have found a security vulnerability,
please report it privately. **Do not open a public issue, pull request or discussion for it.**

Use either channel:

1. **Preferred:** open a
   [private security advisory](https://github.com/infenia/yukta/security/advisories/new) on GitHub.
   It is visible to you and to the repository's maintainers and security managers, and it lets us work on a fix with you in a private fork.
2. **Email:** send the report to **arun@infenia.com**.

### What to include in your report

* A detailed description of the vulnerability.
* Steps to reproduce the issue, or a proof of concept.
* The affected version, module and configuration.
* Potential impact of the vulnerability.
* Any suggested fixes or mitigations.

### Our Process

| Step | Target |
| ---- | ------ |
| Acknowledge your report | Within 2 business days |
| Initial assessment and severity (CVSS) | Within 7 days |
| Fix or mitigation for a confirmed critical or high issue | Within 30 days |
| Fix or mitigation for a confirmed medium or low issue | In the next regular release |

1. We acknowledge receipt of your report and tell you who is handling it.
2. We reproduce the issue, determine its severity and tell you the result. If we decide it is not
   a vulnerability, we explain why.
3. If confirmed, we develop and test a fix privately and keep you informed of progress.
4. We agree a disclosure date with you. We aim to disclose within 90 days of the report at most,
   sooner once a fix is released.
5. We publish the fix in a release, and a GitHub security advisory (with a CVE where applicable)
   that describes the issue, the affected and fixed versions, and any workaround.
6. We credit you in the advisory and release notes unless you prefer to remain anonymous.

### Release notes and advisories

Every release whose changes fix a publicly known vulnerability (one with a CVE or a published
advisory) names it in the release notes, with its identifier and the affected versions. This
includes vulnerabilities fixed by updating a dependency.

## Scope

In scope: the Yukta server, the `cli`, the official plugins, the web UI, and the container image
and release artifacts we publish.

Out of scope:

* Anything that requires the `ADMIN` role, or access to a server that has no API key. Defining a
  workflow is a trusted operation by design, because a workflow can run commands, scripts and SQL.
  See [Security](docs/operations/security.mdx) for the trust model.
* Findings that need a deployment we document as unsafe, for example exposing the server on a
  network without an API key or without TLS in front of it.
* Denial of service by resource exhaustion from an authenticated `ADMIN` user.

## Hardening Guidance

How to expose the server safely, how roles and API keys work, and the settings that bound what a
workflow may do are described in [docs/operations/security.mdx](docs/operations/security.mdx).
The design reasoning behind those controls is in [ASSURANCE.md](ASSURANCE.md).

Thank you for helping keep Yukta secure!
