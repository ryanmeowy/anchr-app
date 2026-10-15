# Security Policy

## Reporting a vulnerability

Please **do not** open a public issue for a security problem.

Use GitHub's private vulnerability reporting:

<https://github.com/ryanmeowy/anchr-app/security/advisories/new>

Include the affected version or commit, a description of the impact, and the smallest
reproduction you can manage. You will get an acknowledgement, and credit in the advisory
unless you prefer otherwise.

## Supported versions

Anchr App is under active development and has not yet cut a maintained release line. Fixes
land on `main`; there are no backports to older tags at this stage.

## Scope notes

Anchr ingests and processes documents that users supply. Treat document content as untrusted
input:

- A malicious document can attempt **prompt injection** against retrieval and generation. If
  you find a document that reliably subverts the citation or scope guarantees, that is a
  security report, not a quality bug.
- Parse failures and unsupported document constructs must fail **visibly**. A document that
  silently produces incomplete content is a correctness and trust problem.
- Access tokens grant API access. Anchr currently uses a single-instance, single-tenant
  deployment model, so a leaked token is equivalent to broad access to that deployment.
- Deployment hardening expectations, including the single-instance constraint and required
  secrets handling, are described in the
  [English technical guide](./docs/technical.en.md).

## Out of scope

- Vulnerabilities in third-party providers (model, embedding, reranking, or object storage)
  that Anchr merely calls. Report those upstream.
- Findings that depend on a misconfigured deployment where the documented hardening steps in
  the technical guide were skipped — report them anyway, but please say so in the report.
