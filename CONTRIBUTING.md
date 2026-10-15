# Contributing to Anchr App

Thanks for considering a contribution. Anchr is the API service behind an evidence-first
document knowledge workspace. This guide covers how to set up the project, what a pull
request is expected to contain, and where to ask questions.

## Before you start

- **Bug reports and feature requests** → open an
  [issue](https://github.com/ryanmeowy/anchr-app/issues/new/choose).
- **Questions, deployment help, ideas** → use
  [Discussions](https://github.com/ryanmeowy/anchr-app/discussions).
- **Security problems** → do **not** open a public issue. See [SECURITY.md](./SECURITY.md).
- **Large changes** (a new retrieval strategy, schema changes, a new provider integration):
  please open an issue or discussion first so the approach can be agreed on before you write
  code. It is much cheaper to redirect a design than to redo a finished pull request.

## Repository layout

Anchr is split across three repositories:

| Repository | Responsibility |
| --- | --- |
| **anchr-app** (this one) | Product workflows: knowledge management, retrieval, answers, Agent runs, activity, configuration. |
| [**anchr-web**](https://github.com/ryanmeowy/anchr-web) | The browser workspace. |
| [**anchr-docling**](https://github.com/ryanmeowy/anchr-docling) | Converts source documents into content the rest of the product can retrieve and read. |

A change that spans more than one repository should be described in **one** issue, with one
pull request per repository cross-linking back to it.

## Development setup

### Prerequisites

- **JDK 21** — the build targets Java 21 (`<java.version>21</java.version>`).
- **Maven 3.9+** — there is no Maven wrapper in this repository, so use your own `mvn`.
- **Docker** — for Elasticsearch, MySQL, and Redis.

### 1. Start infrastructure

```bash
cd docker/infra
cp .env.example .env   # replace every change-me value
docker compose up -d
```

Elasticsearch needs the IK analysis plugin for Chinese text. The
[English technical guide](./docs/technical.en.md) walks through installing it and finishing
the remaining steps.

### 2. Configure the application

Copy the `.env.example` under `docker/app/` and point the application at your infrastructure.
The full settings list, including generation, embedding, reranking, and object storage
providers, is documented in [docs/technical.en.md](./docs/technical.en.md).

### 3. Build and test

```bash
mvn -B -ntp -DskipTests compile   # the first thing CI runs
mvn -B -ntp test                  # unit tests
mvn -B -ntp spring-boot:run       # run locally
```

## What CI checks

Every pull request runs [`.github/workflows/app-ci.yml`](./.github/workflows/app-ci.yml):

1. `mvn -B -ntp -DskipTests compile` on Temurin JDK 21.
2. Docker availability detection, followed by the verification steps that need containers.

Keep the build green. If a change cannot be verified without credentials or external
services, say so explicitly in the pull request rather than leaving the gap implicit.

## Pull request expectations

- **One concern per pull request.** A refactor bundled with a behaviour change is hard to
  review and hard to revert.
- **Explain the why.** The template asks for the problem, the approach, and how you verified
  it. "Fixes a bug" is not useful context for whoever reads the change six months from now.
- **Describe verification honestly.** State which commands you ran and what you observed. If
  something is untested, list it as untested — do not imply coverage you do not have.
- **Keep the diff reviewable.** Large mechanical changes (renames, formatting, import
  ordering) belong in their own commit or their own pull request.
- **Update the docs.** Observable behaviour should be reflected in `docs/` and, when it
  changes the product surface, in the README.

## Commit messages

The history follows [Conventional Commits](https://www.conventionalcommits.org/) informally,
using prefixes such as `feat:`, `fix:`, `docs:`, `test:`, and `refactor:`. Match the
surrounding style and write in the imperative mood ("add retry handling", not "added").

## Coding guidelines

- Match the conventions of the file you are editing. Consistency with surrounding code beats
  personal preference.
- Keep retrieval, generation, and Agent behaviour **observable**. Logging and trace output are
  part of the product, not debug leftovers to strip before pushing.
- Do not silently drop evidence. If a retrieved segment is filtered out, the reason should be
  recoverable from the logs.
- Treat document content as **untrusted input**. Prompt injection through ingested documents
  is a real attack surface for this project.

## Code of conduct

Participation is covered by our [Code of Conduct](./CODE_OF_CONDUCT.md).

## License

By contributing, you agree that your contributions are licensed under the
[MIT License](./LICENSE).
