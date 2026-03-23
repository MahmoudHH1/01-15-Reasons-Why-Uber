# 01-15-Reasons-Why-Uber

## Team Setup (Required for Every Member)

After cloning the repository, run this once from the **project root**:

```bash
mvn install -DskipTests
```

This installs the Git hooks that enforce branch naming and commit message conventions on your machine. **Skip this step and your commits/branches won't be validated locally.**

---

## Conventions

### Branch Naming

```
feat/<service>/<feature-name>/<student-id>
```

| Part | Example |
|------|---------|
| `<service>` | `user-service`, `ride-service`, `driver-service`, `location-service`, `payment-service` |
| `<feature-name>` | `S1-F1`, `search-users`, `add-entity` |
| `<student-id>` | `55-8078` |

Full example: `feat/user-service/S1-F1/55-8078`

### Commit Message Format

```
type(service): description (student-id)
```

Examples:
```
feat(user-service): add User entity model (55-8078)
fix(ride-service): fix null handling in search query (55-8078)
```

Allowed types: `feat`, `fix`, `docs`, `refactor`, `test`, `chore`, `bugfix`, `hotfix`, `release`

> The only exception is the initial project setup commit: `init: project setup by (Name_ID)`

---

## How Enforcement Works

Once you have run `mvn install -DskipTests`, Git hooks are active on your machine and will automatically detect violations.

### Wrong branch name
If you create a branch that does not follow the naming convention, the hook **immediately deletes the branch** and switches you back to your previous branch:
```
Branch 'my-feature' does not follow the required naming convention.

   Required : feat/<service>/<feature-name>/<student-id>
   Example  : feat/user-service/S1-F1/55-8078
```
Simply create the branch again with the correct name.

### Wrong commit message
If your commit message does not follow the required format, the hook **aborts the commit** before it is recorded:
```
Commit message does not follow the required format.

   Required : type(service): description (student-id)
   Example  : feat(user-service): add User entity model (55-8078)
```
Fix the message and commit again — nothing is lost.

> Both checks happen instantly on your machine, whether you commit from IntelliJ, PowerShell, or Git Bash.

---

## Prerequisites

- **Git for Windows** (includes Git Bash) — hooks require a bash interpreter
- Make sure IntelliJ is configured to use the Git for Windows executable:
  `Settings → Version Control → Git → Path to Git executable`