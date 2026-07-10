# Repository guidance for Claude Code

## Commit & PR authorship — applies to ALL Ligero repos

These rules apply across every Ligero repository: `ligero`, `ligero-cli`,
`ligero-examples`, and `ligero-docs`.

- **Author every commit as the project owner** — never as an assistant:
  - Name: `Duvan Jamid`
  - Email: `duvanjamid.work@gmail.com`
  - Ensure `git config user.name`/`user.email` match the above (or commit with
    `--author="Duvan Jamid <duvanjamid.work@gmail.com>"`).
- **Do NOT add any AI/assistant references** to anything that gets pushed:
  - no `Co-Authored-By: Claude …` trailer,
  - no `Claude-Session:` / "Generated with Claude Code" lines,
  - no mention of Claude / Anthropic / any assistant in commit messages,
    PR titles or bodies, code comments, or documentation.
- Write commit messages and PR descriptions in plain, first-person-project
  voice (imperative mood for commits), as the author would.
