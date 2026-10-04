---
name: Jackpot semantic search and rewrite
description: Find or rewrite Java code by meaning, not text, with the Apache NetBeans Java engine (javac-resolved types and bindings). A rule is a Java pattern plus conditions; `scan` lists every match in a source tree, `rewrite` replaces them in one verified pass. Use for "where is this method called on that type" and for changes that repeat across files or depend on types, overloads or scopes.
compatibility: Requires Java 21 or newer, bash, and the jackpot tool (bundled in the release zip; otherwise scripts/jackpot downloads the pinned ~30 MB release once, SHA-256 verified, into ~/.cache/jackpot).
---
<!--

    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.

-->

# Jackpot

Jackpot is a search-and-rewrite tool for Java that works on resolved javac
syntax trees. It is the batch front end of the Apache NetBeans Java engine —
the same parser, type attribution and rewriting machinery behind the IDE's
inspections and refactorings — packaged as a standalone command. You write one
**rule**: a Java pattern, optional conditions, and optionally a replacement.

`scan` reports every place the rule matches across a source tree; `rewrite`
replaces them in a single pass.

Matching is by binding and type, not by text, so an overloaded method, a
same-named symbol in another scope, or identical-looking text in an
unrelated context are told apart.

Rule of thumb: if the question is "where is P used, on this type" or the
change is "replace every P with R", write a rule and run it once. A rule
without a replacement is a query — a grep that understands Java.

## When to use it — and when not

Use Jackpot when the match depends on **meaning**: the receiver's type, which
overload is called, whether a variable is used in a block, what the enclosing
class or package is. A regexp or a syntax-only tool can find `foo(x)`; a rule
can find *this* `foo` on *this* resolved type, and leave the identically
spelled call on an unrelated class alone. That holds for searching as much as
for rewriting: "all `get(0)` calls on a `List`", "all methods of this class",
"every `size() == 0` on a `Collection`" are one `scan` each.

Do not reach for it when the change is purely about **text** — renaming a
string, swapping `System.err` for `System.out`, reformatting — and no condition
is needed. A plain text edit is also much faster; Jackpot costs a few seconds
of startup (depending on number of files).

Operations that are positional rather than pattern-shaped — "extract exactly
this selection", "move this file's class" — are not what the rule language is
for; use a language-aware refactoring tool or your editor.

## Commands

```
jackpot scan     [context] <rules> <root>...          # report matches; never writes
jackpot rewrite  [context] <rules> [--dry-run|--diff-only] <root>...
jackpot try      <rules> --java '<snippet>'           # does the rule match this code? if not, why?
jackpot inspections                                    # built-in inspections usable as rules
jackpot doctor   [context] <root>...                   # the context the tool would use
```

- `<rules>`: `--rules -` (stdin — the usual form), `--rule '<text>'`
  (repeatable one-liners), `--rules <file>`, or `--inspection "<name>"`.
- `[context]`: `--source <level>` (the tree's real language level — the
  default is 1.8), `--classpath <jars>` (`:`-separated; required when a rule
  names a library type), `--sourcepath`, `--group "<flags> <root>"` for a
  second root with its own context. For a Maven or Gradle project, `--maven
  [dir]` / `--gradle [dir]` ask the build tool for all of that (offline, no
  compile, no download; adds the build tool's startup, ~5 s for Maven). If you
  already know the classpath — from your own build setup — just pass it; the
  build-tool flags are a convenience, not a requirement.
- `jackpot context --maven > jackpot.ctx` prints the derived flags, one per
  line; `jackpot scan @jackpot.ctx …` reuses them (standard Java `@argfile`
  syntax) without spawning Maven again.
- `--json` makes stdout a single JSON document (matches with 1-based
  `[line, column]` ranges and the matched text, per-file changes, the unified
  diff, diagnostics with stable codes, a summary). Human-readable otherwise;
  diagnostics always go to stderr as `severity[CODE]: message`.
- Exit codes: `0` ok · `1` usage · `2` rule error · `3` context problem ·
  `4` rewrite incomplete · `5` matches found (`scan --fail-on-match`, for CI).
- Needs Java 21 or newer. Run the tool through this skill's `scripts/jackpot`:
  it uses the launcher bundled next to this file (when the skill was installed
  from the release zip), else `$JACKPOT` or `jackpot` on `PATH`, else it
  downloads the pinned release once (~30 MB, SHA-256 verified) into
  `~/.cache/jackpot` — set `JACKPOT_NO_DOWNLOAD=1` to forbid that. It picks a
  Java 21+ runtime (`JACKPOT_JAVA_HOME` to override). Releases:
  https://github.com/emilianbold/jackpot/releases

## The loop

```sh
# 1. check the rule against a snippet of the code you expect to change
jackpot try --source 21 --rules - --java 'List<String> l = null; boolean e = l.size() == 0;' <<'EOF'
"Prefer Collection.isEmpty":
$c.size() == 0 :: $c instanceof java.util.Collection
=> $c.isEmpty()
;;
EOF

# 2. scan the tree; read the matches
jackpot scan --source 21 --rules rules.txt src

# 3. rewrite on a git-clean tree (or --dry-run first if writes are gated)
jackpot rewrite --source 21 --rules rules.txt src

# 4. review and verify
git diff
<run the project's tests>
```

`rewrite` prints every match, the unified diff, and a summary line
(`changed 1 file(s) (+5/-7 lines) from 7 match(es) in 1 file(s)`). The scan's
matches and the rewrite's edits must agree; if they do not, there is a
diagnostic saying why. `--diff-only` prints a patch that `git apply` accepts
and that is byte-identical to the in-place result.

After rewriting, the tool re-compiles each changed file against the same
classpath and reports errors the rewrite **introduced** (`JACKPOT_INTRODUCED_
ERROR` with file:line:col and javac's message) — e.g. a replacement that calls
a method the receiver does not have. Pre-existing errors are not reported.
This works in `--dry-run` too; `--no-verify` skips it. Fully qualified names
in a replacement are imported and shortened by the engine automatically.

## Reading diagnostics

| Code | Meaning / what to do |
|---|---|
| `JACKPOT_RULE_UNTERMINATED` | the last rule lacks `;;` |
| `JACKPOT_RULE_ERROR` at `origin:line:col` | parse error; "unknown condition function" lists the supported ones |
| `JACKPOT_RULE_OR` / `_BRACE_TYPE` / `_UNSUPPORTED_CONDITION` | `\|\|` in a condition, `$x{Type}`, `matches(`: each message names the working form |
| `JACKPOT_RULE_EMBEDDED_JAVA` | the rule contains `<? … ?>` Java, refused by default |
| `JACKPOT_NO_ROOTS` / `JACKPOT_ROOT_NOT_FOUND` | a source root is missing |
| `JACKPOT_CLASSPATH_MISSING` (doctor) | a `--classpath` entry does not exist |
| `JACKPOT_MAVEN_*` / `JACKPOT_GRADLE_*` (exit 3) | the build tool is missing, or dependencies are not in the local cache yet (run the project's build once) — or pass `--source`/`--classpath`/roots yourself |
| `JACKPOT_SOURCE_LEVEL_UNKNOWN` | the build files do not state a level; add `--source` |
| `JACKPOT_NO_MATCHES` (info) | zero matches; usually `--source` or a type missing from `--classpath` — use `try` |
| `JACKPOT_NO_MATCH` (try) | says whether the *pattern* failed or which *condition* rejected it |
| `JACKPOT_INTRODUCED_ERROR` (rewrite) | the rewritten file has a compile error it did not have before; the replacement is wrong for that receiver |
| `JACKPOT_MULTIPLE_FIXES` | two `=>` alternatives both applied to a match; the first was used — make their conditions exclusive |
| `JACKPOT_FIX_FAILED` (exit 4) | the engine could not apply a fix; **that file was left unchanged**, including other rules' edits; rerun with `--debug` |

## The rule language

One rule per block, terminated by `;;`:

```
["display name":] <pattern> [<!options>] [:: <conditions>]
=> <replacement> [<!options>] [:: <fix-conditions>]
=> <alternative replacement> [:: <fix-conditions>]
;;
```

- Pattern and replacement are Java snippets: an expression, a statement, or a
  block. The pattern matches a syntax subtree; the replacement is spliced in.
- `//` and `/* ... */` comments are allowed anywhere.
- The display name is optional. Without one, matches are reported as
  `TODO_No_display_name` (cosmetic).
- With no `=>` at all, the rule is a query: scan reports it, rewrite ignores it.
- An empty replacement (`=>` directly followed by `;;`) **deletes** the
  matched statement. An expression pattern matches inside an expression
  statement, so `System.gc() => ;;` deletes the whole `System.gc();` line.
- `<!key=value>` options: `description=...`, `minSourceVersion=N`,
  `suppress-warnings=Name`; on a fix, `warning='text'` or `error='text'`
  attaches a message to the report.

### Variables

`$name` binds the node at that position; the same `$name` in the replacement
or conditions refers to that binding. Any identifier works, including `$1`.
A variable in a type position (`$T x = ...`) binds a type.

A trailing `$` makes the variable match a *list* of zero or more nodes:
`$args$` in an argument list, `$params$` in a parameter list, `$stmts$` for a
run of statements in a block, `$mods$` for modifiers, `$else$` for an optional
`else` branch.

### Conditions

Conditions follow `::`, combine with `&&`, and negate with `!`. There is no
`||` — write two rules instead (the tool rejects it with `JACKPOT_RULE_OR`).
The pattern condition gates the match; a fix condition gates that one
replacement.

| Condition | True when |
|---|---|
| `$x instanceof fully.qualified.Type` | `$x` resolves to that type or a subtype |
| `referencedIn($x, $stmts$)` | `$x` is used anywhere inside `$stmts$` |
| `matchesAny($x, "pat", ...)` | `$x` itself matches one of the patterns (e.g. `"$v"`, `"0"`, `"System.gc()"`) |
| `containsAny($x, "pat", ...)` | a pattern matches somewhere inside `$x` |
| `matchesWithBind($x, "pat")` | as `matchesAny`, also binding the pattern's variables |
| `hasModifier($x, Modifier.STATIC)` | declared element has the modifier |
| `elementKindMatches($x, ElementKind.METHOD)` | element kind test |
| `inClass("fqn", ...)` / `inPackage("pkg", ...)` | the match is lexically inside |
| `isNullLiteral($x)` | `$x` is the `null` literal |
| `isAvailable("fqn.Type.method(ParamType)")` | that API exists in the configured classpath |
| `sourceVersionGE(n)` / `sourceVersionLE(n)` | language-level gate |

`Modifier`, `ElementKind` and `SourceVersion` constants resolve without
imports. Sub-patterns passed to `matchesAny` and friends are themselves
patterns, so use `"$v"` to mean "any single node", not a bare identifier
name. Type constraints are written `:: $x instanceof Type`; the legacy
`$x{Type}` form is rejected.

### No embedded Java

A rule file may contain `<? ... ?>` blocks of Java that the tool compiles and
runs in-process. The tool refuses them (`JACKPOT_RULE_EMBEDDED_JAVA`) unless
`--allow-embedded-java` is given. Keep rules declarative.

## Examples (all verified against the tool)

Searching — a rule with no `=>` is a query; `scan` lists the matches, `--json`
gives their ranges:

```
"get(0) on a List": $l.get(0) :: $l instanceof java.util.List ;;
"methods of Demo":  $mods$ $ret $name($params$) { $body$; } :: inClass("demo.Demo") ;;
```

Type-constrained rewrite — the typical case. Matches `list.size() == 0` but not an
unrelated class that also happens to have a `size()` method:

```
"Prefer Collection.isEmpty":
$c.size() == 0 :: $c instanceof java.util.Collection
=> $c.isEmpty()
;;
```

Statement lists and a binding-aware condition — converts only loops whose
body does not use the index, and re-indents the moved body:

```
"Enhanced for":
for (int $i = 0; $i < $array.length; $i++) {
    $T $var = $array[$i];
    $stmts$;
} :: !referencedIn($i, $stmts$)
=>
for ($T $var : $array) {
    $stmts$;
}
;;
```

Alternatives with mutually exclusive fix conditions — picks `getFirst()` on
source 21+, `iterator().next()` below:

```
"First element":
$l.get(0) :: $l instanceof java.util.List
=> $l.getFirst() :: sourceVersionGE(21)
=> $l.iterator().next() :: !sourceVersionGE(21)
;;
```

Query and deletion:

```
"Explicit gc": System.gc() ;;
"Remove explicit gc": System.gc(); => ;;
```

Shape-only rewrites work too, but need no semantics — a syntax-level tool
handles these equally well. (`Integer.valueOf` also changes reference
identity compared with the constructor; the tool does not check that.)

```
new Integer($i) => Integer.valueOf($i) ;;
System.err.println($fmt) => System.out.println($fmt) ;;
```

Built-in inspections are rules too — `jackpot inspections` lists ~200
(lambda conversion, `var`, try-with-resources, boxing, …):

```sh
jackpot rewrite --source 21 --inspection "Boxed Primitive instantiation" src
```

## Gotchas (verified)

- **A failing rewrite blocks the whole file.** If the engine cannot apply one
  fix in a file, none of that file's rewrites are written — including other
  rules'. You get `JACKPOT_FIX_FAILED` and exit 4; treat that file as
  unchanged and rerun with `--debug` for the cause.
- **Make fix alternatives mutually exclusive.** When two `=>` alternatives are
  both applicable to the same match, the first is applied and you get
  `JACKPOT_MULTIPLE_FIXES`. Gate every alternative with a condition.
- **`rewrite` is not one transaction.** Files are committed one at a time;
  there is no all-or-nothing write across the tree. Review with `git diff`.
- **`--since-diff <patch>` keys on the match's first line.** It restricts
  matching to lines the patch added; a multi-line match whose first line was
  not added is skipped.
- **A rewrite is not a behavior guarantee.** The AST is verified; semantics are
  yours. `new Integer(i)` → `Integer.valueOf(i)` can change reference
  identity. Run the project's tests.

## When it finds nothing

Run `jackpot try` with the rule and a snippet of the code you expect to
match. It tells you whether the pattern's shape is wrong or whether the
conditions rejected it. The usual causes:

- A type named in the rule is not resolvable: add the dependency to
  `--classpath`, or drop the constraint and let it resolve from the sources.
- `--source` does not match the tree's real level.
- A sub-pattern inside a condition is a bare identifier (see Conditions).
