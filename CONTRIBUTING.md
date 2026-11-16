# Contributing

Thank you for helping. This repository is one of the BotMaker projects (https://github.com/BotMakerDev).

## The contributor agreement

BotMaker is LiQiyeDev's, published under PolyForm Noncommercial 1.0.0 (`LICENSE`) and also licensed
commercially. So that your work can go wherever the rest of the code goes, a pull request from outside is merged
only once its author has signed the BotMaker Contributor License Agreement.

The CLA Assistant check on your first pull request links the agreement and asks you to sign it with a comment. You
sign once, and it covers your later pull requests to every BotMaker repository. You keep the copyright of what you
write. The agreement lets LiQiyeDev license it under any terms, commercial ones included.

## Before a pull request

- Open an issue first for anything larger than a fix, so we can agree on the shape before you build it.
- Keep a pull request to one change, with its tests.
- A new Java file starts with the two SPDX lines every other one has:

  ```java
  // SPDX-FileCopyrightText: 2026 LiQiyeDev
  // SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
  ```

- Code you did not write, such as a library copied in or a snippet with its own license, says so in the pull
  request. It keeps its own license, listed in `REUSE.toml`.
- Add a line under `## [Unreleased]` in `CHANGELOG.md` when the change is one a user would notice.
