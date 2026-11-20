# Developer guide

## Pre-install

### Basic dependencies

* [OpenJDK 25](https://openjdk.org/) or newer
* [Apache Maven](https://maven.apache.org/)
* [Node.js 24 and npm](https://nodejs.org/)
* Docker Compose, or Podman with Compose

PostgreSQL and Keycloak are started by `compose.yaml`; see [BUILDING.md](BUILDING.md). To use your own PostgreSQL instead, create a database and user, and set `DB_URL`, `DB_USER` and `DB_PASSWORD`:

```sh
createuser -P cisys
createdb -E UTF8 -O cisys cisys
```

The default password is `cisys`. Enable access in `pg_hba.conf` and reload.

### Generate the manual

The application shows the manual at `/manual`, bundled from `docs/manual/en` by the frontend build. The steps below generate the HTML and PDF versions.

1. Download dependencies

``` sh
dnf install pandoc texlive-scheme-basic
```

2. Download Eisvogel

    Use the command `pandoc --version` to locate the user data directory. On Fedora systems, this directory is typically located at `$HOME/.local/share/pandoc`.

    Download the `Eisvogel` template for `pandoc` from the [pandoc-latex-template](https://github.com/Wandmalfarbe/pandoc-latex-template) repository. For a standard installation, you can follow the steps outlined below.

```sh
    wget https://github.com/Wandmalfarbe/pandoc-latex-template/releases/download/v3.4.0/Eisvogel-3.4.0.tar.gz
    tar -xzf Eisvogel-3.4.0.tar.gz
    mkdir -p $HOME/.local/share/pandoc/templates
    mv Eisvogel-3.4.0/eisvogel.latex $HOME/.local/share/pandoc/templates/
```

3. Add packages for LaTeX

```sh
    dnf install 'tex(fvextra.sty)' 'tex(footnote.sty)' 'tex(footnotebackref.sty)' 'tex(pagecolor.sty)' 'tex(hardwrap.sty)' 'tex(mdframed.sty)' 'tex(sourcesanspro.sty)' 'tex(ly1enc.def)' 'tex(sourcecodepro.sty)' 'tex(titling.sty)' 'tex(csquotes.sty)' 'tex(zref-abspage.sty)' 'tex(needspace.sty)' 'tex(selnolig.sty)'
```

4. Generate the manual

```sh
cd docs/manual/en
./generate-manual.sh
```

This creates `target/cisys-en.html` and `target/cisys-en.pdf`. If the Eisvogel template is stored in a non-standard location, set `EISVOGEL_TEMPLATE` before running the script.

If PDF generation fails with `Mismatched LaTeX support files detected`, refresh the XeLaTeX format and rerun:

```sh
fmtutil-user --byfmt xelatex
```

If your TeX installation is managed system-wide and the user refresh does not help, rebuild the system format instead with `sudo fmtutil-sys --byfmt xelatex`.

## Build

See [BUILDING.md](BUILDING.md) for the current development, build, test and frontend commands.

## Keycloak OIDC authentication

Cisys uses **Keycloak** for sign-in and bearer-token authentication.

* The frontend is a public client (`cisys-ui`) using the authorization-code flow with PKCE.
* The backend is a Quarkus OIDC `service` application. It reads roles from the access token claim `realm_access/roles` and uses the immutable `sub` claim as the principal and project member subject. The project team is stored per user per project, with a project-level role.
* The realm roles are `member`, `lead` and `admin`.

The development realm is `contrib/keycloak/realm.json`. It is imported on the first Keycloak start only.

See [SECURITY.md](SECURITY.md) for the role-to-endpoint mapping.

## Project layout

| Path                                     | Content                                         |
| :--------------------------------------- | :---------------------------------------------- |
| `src/backend/main/java/ai/mnemosyne/cisys` | Quarkus backend (entities, REST resources, runner) |
| `src/backend/main/resources`             | Quarkus configuration                           |
| `src/backend/test`                       | Backend tests (PostgreSQL via Dev Services, `@TestSecurity`) |
| `src/frontend`                           | React frontend (Vite, Tailwind)                 |
| `contrib/keycloak`                       | Development Keycloak realm and login theme      |
| `docs`                                   | Documentation, roadmap, screenshots and manual  |
| `compose.yaml`                           | Local PostgreSQL and Keycloak                   |
| `.github/workflows`                      | CI: backend tests, frontend lint and build      |

## Logging levels

| Level | Description |
| :------- | :------ |
| TRACE | Information for developers including values of variables |
| DEBUG | Higher level information for developers - typically about flow control and the value of key variables |
| INFO | A user command was successful or general health information about the system |
| WARN | A user command didn't complete correctly so attention is needed |
| ERROR | Something unexpected happened - try to give information to help identify the problem |
| FATAL | We can't recover - display as much information as we can about the problem and `exit(1)` |

Never log secrets, tokens or Git credentials.

## Basic git guide

Here are some links that will help you

* [How to Squash Commits in Git](https://www.git-tower.com/learn/git/faq/git-squash)
* [ProGit book](https://github.com/progit/progit2/releases)

### Start by forking the repository

This is done by the "Fork" button on GitHub.

### Clone your repository locally

This is done by

```sh
git clone git@github.com:<username>/cisys.git
```

### Add upstream

Do

```sh
cd cisys
git remote add upstream https://github.com/mnemosyne-systems/cisys.git
```

### Do a work branch

```sh
git checkout -b mywork main
```

### Make the changes

Remember to verify the compile and execution of the code.

Before opening a pull request run

```sh
mvn test
cd src/frontend
npm run lint
npm run build
```

`npm install` also installs the Husky hooks: `pre-commit` runs ESLint on staged frontend files. There is no `pre-push` hook; run the full checks (frontend lint and backend tests) with `make test`. If the hooks do not fire, run `make setup` from the repository root. Bypass once with `--no-verify` if needed.

### AUTHORS

Remember to add your name to the following files,

```
AUTHORS
docs/manual/en/97-acknowledgement.md
```

in your first pull request

### Multiple commits

If you have multiple commits on your branch then squash them

``` sh
git rebase -i HEAD~2
```

for example. It is `p` for the first one, then `s` for the rest

### Rebase

Always rebase

``` sh
git fetch upstream
git rebase -i upstream/main
```

### Force push

When you are done with your changes force push your branch

``` sh
git push -f origin mywork
```

and then create a pull requests for it

### Repeat

Based on feedback keep making changes, squashing, rebasing and force pushing

### PTAL

When you are working on a change put it into Draft mode, so we know that you are not happy with it yet.

Please, send a PTAL to the Committer that were assigned to you once you think that your change is complete. And, of course, take it out of Draft mode.

### Undo

Normally you can reset to an earlier commit using `git reset <commit hash> --hard`. But if you accidentally squashed two or more commits, and you want to undo that, you need to know where to reset to, and the commit seems to have lost after you rebased.

But they are not actually lost - using `git reflog`, you can find every commit the HEAD pointer has ever pointed to. Find the commit you want to reset to, and do `git reset --hard`.

## Verification status

* Backend tests (`mvn test`) and the frontend lint and production build pass in the development workspace.
* Keycloak 26.8.2 tokens for Member, Lead and Admin, and private-project visibility, were tested through the REST API against a real local Keycloak and PostgreSQL 18 on 2026-11-07.
* An opt-in smoke test fetches the public `octocat/Hello-World` repository via JGit and runs an Alpine container through Docker: `CISYS_RUNNER_SMOKE=true mvn -Dtest=RunnerSmokeTest test`.
* Not yet verified: a real private Git provider, Podman, Windows/WSL, and the full Compose stack end to end.

See [ROADMAP.md](ROADMAP.md) for what is not implemented yet.
