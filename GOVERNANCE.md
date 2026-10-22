# Governance

This document describes how the Yukta project is run: who makes decisions, what each role is
responsible for, and how the project continues if a maintainer is unavailable.

Yukta is an open-source project developed under **Infenia Private Limited** and licensed as
described in [LICENSING.md](LICENSING.md).

## Roles and Responsibilities

### Users

Anyone who runs Yukta. Users report bugs, ask questions in
[Discussions](https://github.com/infenia/yukta/discussions) and request features through issues.

### Contributors

Anyone who submits an issue, a pull request, documentation or a review. Contributors follow
[CONTRIBUTING.md](CONTRIBUTING.md) and the [Code of Conduct](CODE_OF_CONDUCT.md), and sign the
[CLA](CLA.md) (or the [corporate CLA](CLA-corporate.md)) before a pull request is merged.

### Maintainers

The people listed in [.github/CODEOWNERS](.github/CODEOWNERS). The file lists the maintainers of
each area, and GitHub requests their review on every pull request that touches that area.

Maintainers are responsible for:

* Triaging issues: setting type, module and priority, as described in
  [.github/PROJECT.md](.github/PROJECT.md).
* Reviewing and merging pull requests, and keeping the `CI Gate` check and the quality gates
  green.
* Handling private vulnerability reports within the targets in [SECURITY.md](SECURITY.md).
* Cutting releases and keeping release notes accurate, including fixed vulnerabilities.
* Enforcing the Code of Conduct.
* Keeping the roadmap and documentation current.

### Project owner

Infenia Private Limited owns the project and its repository and appoints the maintainers. The
owner holds administrative rights on the GitHub organization, the repository secrets and
variables, and the documentation site.

## Decision Making

* **Day to day:** changes are decided in pull requests. A maintainer other than the author
  reviews each change, and a maintainer merges it once the review is positive and `CI Gate` passes.
  A maintainer who is the only person able to review their own change records the reasoning in
  the pull request and waits for review from another maintainer or contributor when the change
  touches `plugin-api`, `messaging`, `build-logic`, security-relevant code or a public API.
* **Larger changes** (new modules, public API or schema changes, security-model changes) start
  as an Epic on the [Yukta Roadmap project](https://github.com/orgs/infenia/projects), with a
  design note in `design/`. Discussion happens on the issue. The change proceeds when the
  maintainers agree.
* **Disagreement:** maintainers seek consensus. If they cannot reach it, the project owner
  decides and records the decision and reasoning on the issue. This tie-break does not apply to
  changes to this document, which follow the rule in "Changes to this Document".
* **Roadmap and priorities** are set by the maintainers in the open, on the roadmap project.

## Becoming a Maintainer

A contributor can be invited to maintain an area after a sustained record of good
contributions and reviews in it. The existing maintainers propose and agree the invitation, the
person accepts, and the project owner grants the access. The new maintainer is added to
`.github/CODEOWNERS` for the areas they look after.

A maintainer can step down at any time by saying so on an issue, and is removed from
`.github/CODEOWNERS`. Maintainers who are inactive for more than six months, or who repeatedly
violate the Code of Conduct, can be removed by the project owner after asking them.

When a maintainer steps down or is removed, the project owner also revokes their access: their
repository, organization and team membership, personal access tokens and deploy keys, and any
repository secrets or credentials they had access to are removed or rotated.

## Access Continuity

The project must not depend on a single person being reachable.

* More than one maintainer holds write access to the repository, and the project owner holds
  administrative rights on the GitHub organization, so repository access does not depend on one
  account.
* Everything needed to build, test, release and run the project is in the repository and
  documented: [docs/building](docs/building), [release/README.md](release/README.md) and
  [.github/WORKFLOWS.md](.github/WORKFLOWS.md). Nothing in a release depends on private
  knowledge of one person.
* Releases are cut by [release-please](https://github.com/googleapis/release-please) and built and
  signed in GitHub Actions. Signing is keyless (Sigstore), so there is no long-lived private key
  that one person must hold. The bot credentials are repository secrets managed by the project
  owner, and the owner rotates them if a person leaves.
* If every maintainer becomes unavailable, the project owner appoints replacements. Anyone may
  also fork the project under its licenses.

## Security of Maintainer Accounts

Maintainers use two-factor authentication on GitHub, and the organization requires it. Commits
and releases come only through pull requests merged into the protected `main` branch, and the
release workflows run in GitHub Actions with provenance attestation.

## Changes to this Document

Changes to governance are proposed in a pull request and need the approval of the project owner
and a majority of the individual maintainers listed in `.github/CODEOWNERS`. Where a team is
listed, its members each count as one maintainer.
