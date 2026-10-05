/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.netbeans.modules.jackpot30.cmdline;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.prefs.Preferences;
import java.util.regex.Pattern;
import joptsimple.ArgumentAcceptingOptionSpec;
import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import org.netbeans.api.java.classpath.ClassPath;
import org.netbeans.api.java.source.CompilationController;
import org.netbeans.api.java.source.ModificationResult;
import org.netbeans.api.lexer.TokenHierarchy;
import org.netbeans.api.lexer.TokenSequence;
import org.netbeans.modules.jackpot30.cmdline.Main.PatchDescription;
import org.netbeans.modules.jackpot30.cmdline.Main.RootConfiguration;
import org.netbeans.modules.java.hints.declarative.DeclarativeHintTokenId;
import org.netbeans.modules.java.hints.declarative.DeclarativeHintsParser;
import org.netbeans.modules.java.hints.jackpot.spi.PatternConvertor;
import org.netbeans.modules.java.hints.providers.spi.HintDescription;
import org.netbeans.modules.java.hints.providers.spi.HintMetadata;
import org.netbeans.modules.java.hints.spiimpl.MessageImpl;
import org.netbeans.modules.java.hints.spiimpl.batch.BatchSearch;
import org.netbeans.modules.java.hints.spiimpl.batch.BatchSearch.BatchResult;
import org.netbeans.modules.java.hints.spiimpl.batch.BatchSearch.Folder;
import org.netbeans.modules.java.hints.spiimpl.batch.BatchSearch.Resource;
import org.netbeans.modules.java.hints.spiimpl.batch.BatchSearch.VerifiedSpansCallBack;
import org.netbeans.modules.java.hints.spiimpl.batch.BatchUtilities;
import org.netbeans.modules.java.hints.spiimpl.batch.ProgressHandleWrapper;
import org.netbeans.modules.java.hints.spiimpl.batch.Scopes;
import org.netbeans.modules.java.hints.spiimpl.options.HintsSettings;
import org.netbeans.modules.parsing.impl.indexing.CacheFolder;
import org.netbeans.modules.parsing.impl.indexing.RepositoryUpdater;
import org.netbeans.modules.refactoring.spi.RefactoringElementImplementation;
import org.netbeans.spi.editor.hints.ErrorDescription;
import org.netbeans.spi.editor.hints.Severity;
import org.netbeans.spi.java.hints.HintContext.MessageKind;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.loaders.DataObject;
import org.openide.loaders.DataObjectNotFoundException;
import org.openide.util.Exceptions;

/**
 * Agent-oriented command line: {@code jackpot scan|rewrite|try|inspections|doctor}.
 *
 * <p>Compared to {@link Main} (which it shares the compilation-context synthesis
 * and packaging with) it: takes rules inline ({@code --rules -}, {@code --rule}),
 * never writes in {@code scan}, writes by default in {@code rewrite} with
 * {@code --dry-run}/{@code --diff-only}, reports every match and every change
 * (optionally as JSON), refuses rules with embedded Java unless asked, and
 * turns engine problems into diagnostics and exit codes instead of silence.
 */
public class AgentMain {

    static final int EXIT_OK = 0;
    static final int EXIT_USAGE = 1;
    static final int EXIT_RULE_ERROR = 2;
    static final int EXIT_CONTEXT = 3;
    static final int EXIT_REWRITE_FAILED = 4;
    static final int EXIT_MATCHES_FOUND = 5;

    static final String TOOL_VERSION = "31.0-agent.1";

    private static final Set<String> COMMANDS = new TreeSet<>(Arrays.asList("scan", "rewrite", "try", "inspections", "doctor", "context", "help"));

    public static void main(String... args) throws Exception {
        System.exit(run(args));
    }

    static boolean isAgentCommand(String[] args) {
        return args.length > 0 && COMMANDS.contains(args[0]);
    }

    public static int run(String... args) throws Exception {
        if (args.length == 0 || "help".equals(args[0]) || "--help".equals(args[0]) || "-h".equals(args[0])) {
            printUsage(System.out);
            return args.length == 0 ? EXIT_USAGE : EXIT_OK;
        }

        String command = args[0];

        if (!COMMANDS.contains(command)) {
            System.err.println("jackpot: unknown command '" + command + "'");
            printUsage(System.err);
            return EXIT_USAGE;
        }

        String[] rest;
        try {
            rest = Main.inlineParameterFiles(Arrays.copyOfRange(args, 1, args.length)); //@file argfiles
        } catch (OptionException ex) {
            System.err.println("jackpot " + command + ": cannot read argument file: " + ex.getCause());
            return EXIT_USAGE;
        }

        System.setProperty("netbeans.user", Files.createTempDirectory("jackpot-user").toString());
        System.setProperty("SourcePath.no.source.filter", "true");

        OptionParser parser = new OptionParser();
        Main.GroupOptions groupOptions = Main.setupGroupParser(parser);
        ArgumentAcceptingOptionSpec<String> group = parser.accepts("group", "additional root with its own context: \"<flags> <root>\"").withRequiredArg().ofType(String.class);
        ArgumentAcceptingOptionSpec<String> rulesOpt = parser.accepts("rules", "rule file, or - for stdin").withRequiredArg().ofType(String.class);
        ArgumentAcceptingOptionSpec<String> ruleOpt = parser.accepts("rule", "inline rule text (repeatable)").withRequiredArg().ofType(String.class);
        ArgumentAcceptingOptionSpec<String> inspectionOpt = parser.accepts("inspection", "built-in inspection to run, by name").withRequiredArg().ofType(String.class);
        ArgumentAcceptingOptionSpec<String> javaOpt = parser.accepts("java", "(try) Java snippet to match the rule against").withRequiredArg().ofType(String.class);
        ArgumentAcceptingOptionSpec<File> javaFileOpt = parser.accepts("java-file", "(try) file with the Java snippet").withRequiredArg().ofType(File.class);
        ArgumentAcceptingOptionSpec<File> sinceDiff = parser.accepts("since-diff", "only match code on lines added by this unified diff").withRequiredArg().ofType(File.class);
        ArgumentAcceptingOptionSpec<File> cache = parser.accepts("cache", "persistent index cache directory").withRequiredArg().ofType(File.class);
        ArgumentAcceptingOptionSpec<String> mavenOpt = parser.accepts("maven", "derive roots, source level and classpath from the Maven project in <dir> (default .), offline").withOptionalArg().ofType(String.class);
        ArgumentAcceptingOptionSpec<String> gradleOpt = parser.accepts("gradle", "derive roots, source level and classpath from the Gradle project in <dir> (default .), offline").withOptionalArg().ofType(String.class);
        parser.accepts("json", "machine-readable output on stdout");
        parser.accepts("dry-run", "(rewrite) compute and report changes, write nothing");
        parser.accepts("diff-only", "(rewrite) like --dry-run, print only the unified diff");
        parser.accepts("fail-on-match", "(scan) exit " + EXIT_MATCHES_FOUND + " when there are matches");
        parser.accepts("allow-embedded-java", "accept rules containing <? ... ?> Java blocks (they run in-process)");
        parser.accepts("no-verify", "(rewrite) skip re-compiling changed files to report introduced errors");
        parser.accepts("debug", "keep engine logging enabled");
        parser.accepts("help", "print help");

        OptionSet parsed;

        try {
            parsed = parser.parse(rest);
        } catch (OptionException ex) {
            System.err.println("jackpot " + command + ": " + ex.getMessage());
            return EXIT_USAGE;
        }

        if (parsed.has("help")) {
            printUsage(System.out);
            parser.printHelpOn(System.out);
            return EXIT_OK;
        }

        boolean json = parsed.has("json");
        Report report = new Report(command, json ? System.out : null);

        if (!parsed.has("debug")) {
            Main.prepareLoggers();
        }

        File cacheDir = parsed.valueOf(cache);
        boolean deleteCacheDir = false;

        try {
            if (cacheDir == null) {
                cacheDir = Files.createTempDirectory("jackpot-cache").toFile();
                deleteCacheDir = true;
            } else if (cacheDir.isFile() || (cacheDir.isDirectory() && cacheDir.list().length > 0 && !new File(cacheDir, "segments").exists())) {
                report.diagnostic("error", "JACKPOT_CACHE_INVALID", null, "--cache must be an empty directory or one created by this tool: " + cacheDir);
                return report.finish(EXIT_USAGE);
            }
            cacheDir.mkdirs();
            CacheFolder.setCacheFolder(FileUtil.toFileObject(FileUtil.normalizeFile(cacheDir)));
            org.netbeans.api.project.ui.OpenProjects.getDefault().getOpenProjects();
            RepositoryUpdater.getDefault().start(false);

            BuildTool build = BuildTool.from(parsed, mavenOpt, gradleOpt);

            switch (command) {
                case "inspections":
                    return inspections(report);
                case "context":
                    return context(report, build);
                case "doctor":
                    return doctor(report, parsed, groupOptions, group, build);
                case "try":
                    return tryRule(report, parsed, groupOptions, build, rulesOpt, ruleOpt, javaOpt, javaFileOpt);
                default:
                    return scanOrRewrite(command, report, parsed, groupOptions, group, build, rulesOpt, ruleOpt, inspectionOpt, sinceDiff);
            }
        } finally {
            if (deleteCacheDir) {
                FileObject cacheDirFO = FileUtil.toFileObject(cacheDir);
                if (cacheDirFO != null) {
                    cacheDirFO.delete();
                }
            }
        }
    }

    private static void printUsage(PrintStream out) {
        out.println("usage: jackpot <command> [options] <source-root>...");
        out.println();
        out.println("commands:");
        out.println("  scan         report where rules match; never writes");
        out.println("  rewrite      apply rules in place and report every change (--dry-run: report only)");
        out.println("  try          match rules against a Java snippet (--java '...' or --java-file)");
        out.println("  inspections  list the built-in inspections usable with --inspection");
        out.println("  doctor       report the compilation context the tool would use");
        out.println("  context      print the context derived from a build (--maven/--gradle) as flags, for an @file");
        out.println();
        out.println("rules:        --rules <file> | --rules - (stdin) | --rule '<text>' (repeatable) | --inspection <name>");
        out.println("context:      --source <level> --classpath <jars> [--sourcepath <roots>] [--group \"<flags> <root>\"]");
        out.println("              --maven [dir] | --gradle [dir]   ask the build tool (offline; no compile, no download)");
        out.println("              @file                            read arguments from a file, one per line");
        out.println("output:       --json (stdout is then JSON only; logs go to stderr)");
        out.println("exit codes:   0 ok  1 usage  2 rule error  3 context problem  4 rewrite incomplete  5 matches found (--fail-on-match)");
        out.println();
        out.println("example:");
        out.println("  jackpot rewrite --source 21 --rules - src <<'EOF'");
        out.println("  \"Prefer Collection.isEmpty\":");
        out.println("  $c.size() == 0 :: $c instanceof java.util.Collection");
        out.println("  => $c.isEmpty()");
        out.println("  ;;");
        out.println("  EOF");
    }

    // --- commands -------------------------------------------------------------

    private static int inspections(Report report) throws IOException {
        Map<String, String> byName = new TreeMap<>();
        for (Entry<HintMetadata, Collection<? extends HintDescription>> e : Main.listHints(ClassPath.EMPTY, ClassPath.EMPTY).entrySet()) {
            if (e.getKey().kind != org.netbeans.spi.java.hints.Hint.Kind.INSPECTION) continue;
            byName.put(e.getKey().displayName, e.getKey().id);
        }
        List<Object> list = new ArrayList<>();
        for (Entry<String, String> e : byName.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", e.getKey());
            m.put("id", e.getValue());
            list.add(m);
            report.human(e.getKey());
        }
        report.put("inspections", list);
        report.summary("inspections", byName.size());
        return report.finish(EXIT_OK);
    }

    private static int doctor(Report report, OptionSet parsed, Main.GroupOptions groupOptions, ArgumentAcceptingOptionSpec<String> group, BuildTool build) throws Exception {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("tool", TOOL_VERSION);
        env.put("java", System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        env.put("javaHome", System.getProperty("java.home"));
        report.put("environment", env);
        report.human("tool:       " + TOOL_VERSION);
        report.human("java:       " + env.get("java"));

        List<NamedGroup> groups = parseGroups(parsed, groupOptions, group, build, report);
        if (groups == null) return report.finish(EXIT_CONTEXT);
        int problems = 0;
        List<Object> ctx = new ArrayList<>();
        int gi = 0;
        for (NamedGroup ng : groups) {
            RootConfiguration rc = ng.rc;
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("name", ng.name);
            List<String> roots = new ArrayList<>();
            int javaFiles = 0;
            for (Folder f : rc.rootFolders) {
                roots.add(display(f.getFileObject()));
                javaFiles += countJava(f.getFileObject());
            }
            g.put("roots", roots);
            g.put("javaFiles", javaFiles);
            g.put("source", rc.sourceLevel);
            boolean levelOk = Pattern.compile(Main.ACCEPTABLE_SOURCE_LEVEL_PATTERN).matcher(rc.sourceLevel).matches();
            List<String> cp = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            for (ClassPath.Entry e : rc.compileCP.entries()) {
                File f = FileUtil.archiveOrDirForURL(e.getURL());
                String d = f != null ? display(f.toPath()) : e.getURL().toString();
                cp.add(d);
                if (f != null && !f.exists()) missing.add(d);
            }
            g.put("classpath", cp);
            g.put("classpathMissing", missing);
            ctx.add(g);
            report.human("group " + gi + " (" + ng.name + "):");
            report.human("  roots:      " + (roots.isEmpty() ? "(none)" : String.join(", ", roots)) + "  (" + javaFiles + " .java files)");
            report.human("  source:     " + rc.sourceLevel + (levelOk ? "" : "  <- unrecognized"));
            report.human("  classpath:  " + (cp.isEmpty() ? "(empty: rules naming library types will not resolve)" : cp.size() + " entries" + (missing.isEmpty() ? "" : ", " + missing.size() + " missing")));
            if (roots.isEmpty() && groups.size() == 1) {
                report.diagnostic("error", "JACKPOT_NO_ROOTS", null, "no existing source roots given");
                problems++;
            }
            if (!levelOk) {
                report.diagnostic("error", "JACKPOT_SOURCE_LEVEL", null, "unrecognized --source level '" + rc.sourceLevel + "'; use e.g. 17 or 21");
                problems++;
            }
            for (String m : missing) {
                report.diagnostic("error", "JACKPOT_CLASSPATH_MISSING", null, "classpath entry does not exist: " + m);
                problems++;
            }
            gi++;
        }
        report.put("context", ctx);
        return report.finish(problems == 0 ? EXIT_OK : EXIT_CONTEXT);
    }

    private static int tryRule(Report report, OptionSet parsed, Main.GroupOptions groupOptions, BuildTool build,
                               ArgumentAcceptingOptionSpec<String> rulesOpt, ArgumentAcceptingOptionSpec<String> ruleOpt,
                               ArgumentAcceptingOptionSpec<String> javaOpt, ArgumentAcceptingOptionSpec<File> javaFileOpt) throws Exception {
        String snippet;
        if (parsed.has(javaOpt)) {
            snippet = parsed.valueOf(javaOpt);
        } else if (parsed.has(javaFileOpt)) {
            snippet = new String(Files.readAllBytes(parsed.valueOf(javaFileOpt).toPath()), StandardCharsets.UTF_8);
        } else {
            report.diagnostic("error", "JACKPOT_USAGE", null, "try needs --java '<snippet>' or --java-file <file>");
            return report.finish(EXIT_USAGE);
        }

        Rules rules = readRules(parsed, rulesOpt, ruleOpt, report);
        if (rules == null) {
            return report.finish(EXIT_RULE_ERROR);
        }

        Path root = Files.createTempDirectory("jackpot-try");
        try {
            String source = wrapSnippet(snippet);
            Files.write(root.resolve("Try.java"), source.getBytes(StandardCharsets.UTF_8));
            report.put("snippetSource", source);

            //the snippet is its own root; a build tool context only contributes its classpath and source level
            OptionSet ctxParsed = parsed;
            Main.GroupOptions ctxOptions = groupOptions;
            if (build != null) {
                List<BuildContext.Group> bg = build.resolve(report);
                if (bg == null) return report.finish(EXIT_CONTEXT);
                BuildContext.Group g = bg.get(0);
                List<String> args = new ArrayList<>();
                if (g.sourceLevel != null && !parsed.has(groupOptions.source)) { args.add("--source"); args.add(g.sourceLevel); }
                if (!g.classpath.isEmpty()) { args.add("--classpath"); args.add(g.classpathString()); }
                OptionParser cp = new OptionParser();
                ctxOptions = Main.setupGroupParser(cp);
                ctxParsed = cp.parse(args.toArray(new String[0]));
            }
            RootConfiguration rc = new RootConfiguration(ctxParsed, ctxOptions, Collections.singletonList(root.toFile()));
            if (checkPatternResolution(rules, rc, report) > 0) {
                report.put("result", "unresolved");
                report.summary("matches", 0);
                return report.finish(EXIT_RULE_ERROR);
            }
            List<Match> matches = new ArrayList<>();
            List<MessageImpl> problems = new LinkedList<>();
            Iterable<? extends HintDescription> hints = rules.descriptions;
            runScan(rc, hints, rules.settings, null, matches, problems);
            engineProblems(problems, report);

            if (!matches.isEmpty()) {
                report.put("matches", matchesToJson(matches));
                report.put("result", "match");
                for (Match m : matches) {
                    report.human("match: [" + m.rule + "] " + m.text.replace('\n', ' ') + "  (" + m.startLine + ":" + m.startColumn + ")");
                }
                report.summary("matches", matches.size());
                return report.finish(EXIT_OK);
            }

            //no match: is it the pattern or the conditions?
            String unconditioned = stripConditions(rules.text);
            String why;
            if (!unconditioned.equals(rules.text)) {
                Iterable<? extends HintDescription> bare = PatternConvertor.create(unconditioned);
                List<Match> bareMatches = new ArrayList<>();
                if (bare != null) {
                    HintsSettings bareSettings = HintsSettings.createPreferencesBasedHintsSettings(new Main.MemoryPreferences(), false, null);
                    for (HintDescription hd : bare) bareSettings.setEnabled(hd.getMetadata(), true);
                    runScan(rc, bare, bareSettings, null, bareMatches, new LinkedList<>());
                }
                if (!bareMatches.isEmpty()) {
                    report.put("patternOnlyMatches", matchesToJson(bareMatches));
                    report.put("result", "conditions-rejected");
                    //which condition? drop one at a time (bounded: the snippet root is one file, each probe ~0.5 s)
                    List<String> culprits = new ArrayList<>();
                    List<int[]> spans = conditionSpans(rules.text);
                    if (spans.size() > 1 && spans.size() <= 6) {
                        for (int[] span : spans) {
                            String without = rules.text.substring(0, span[0]) + rules.text.substring(span[1]);
                            without = without.replaceAll("::\\s*(&&\\s*)?(?=(=>|;;))", "").replaceAll("&&\\s*(?=(=>|;;))", "").replaceAll("::\\s*&&", "::");
                            Iterable<? extends HintDescription> probe = PatternConvertor.create(without);
                            if (probe == null) continue;
                            HintsSettings probeSettings = HintsSettings.createPreferencesBasedHintsSettings(new Main.MemoryPreferences(), false, null);
                            for (HintDescription hd : probe) probeSettings.setEnabled(hd.getMetadata(), true);
                            List<Match> probeMatches = new ArrayList<>();
                            runScan(rc, probe, probeSettings, null, probeMatches, new LinkedList<>());
                            if (!probeMatches.isEmpty()) culprits.add(rules.text.substring(span[0], span[1]).trim());
                        }
                    } else if (spans.size() == 1) {
                        culprits.add(rules.text.substring(spans.get(0)[0], spans.get(0)[1]).trim());
                    }
                    if (!culprits.isEmpty()) {
                        report.put("rejectingConditions", culprits);
                        why = "the pattern matches " + bareMatches.size() + " place(s) but " + (culprits.size() == 1 ? "this condition rejects it: " : "each of these conditions alone rejects it: ") + String.join(" ; ", culprits) + " - check the types on the classpath and the condition arguments";
                    } else {
                        why = "the pattern matches " + bareMatches.size() + " place(s) but the conditions (after '::') reject them in combination; check types on the classpath and the condition arguments";
                    }
                } else {
                    why = "the pattern itself does not match; check its shape (expression vs. statement, exact method/argument structure)";
                    report.put("result", "no-match");
                }
            } else {
                why = "the pattern does not match; check its shape (expression vs. statement, exact method/argument structure)";
                report.put("result", "no-match");
            }
            report.diagnostic("info", "JACKPOT_NO_MATCH", null, why);
            report.summary("matches", 0);
            return report.finish(EXIT_OK);
        } finally {
            deleteRecursively(root);
        }
    }

    private static int scanOrRewrite(String command, Report report, OptionSet parsed, Main.GroupOptions groupOptions,
                                     ArgumentAcceptingOptionSpec<String> group, BuildTool build, ArgumentAcceptingOptionSpec<String> rulesOpt,
                                     ArgumentAcceptingOptionSpec<String> ruleOpt, ArgumentAcceptingOptionSpec<String> inspectionOpt,
                                     ArgumentAcceptingOptionSpec<File> sinceDiff) throws Exception {
        boolean rewrite = "rewrite".equals(command);
        boolean dryRun = rewrite && (parsed.has("dry-run") || parsed.has("diff-only"));
        boolean diffOnly = rewrite && parsed.has("diff-only");

        List<NamedGroup> named = parseGroups(parsed, groupOptions, group, build, report);
        if (named == null) return report.finish(EXIT_CONTEXT);
        List<RootConfiguration> groups = new ArrayList<>();
        for (NamedGroup ng : named) groups.add(ng.rc);
        report.put("context", contextJson(named));
        int totalRoots = 0;
        for (RootConfiguration rc : groups) totalRoots += rc.rootFolders.size();
        if (totalRoots == 0) {
            report.diagnostic("error", "JACKPOT_NO_ROOTS", null, "no existing source roots given; pass one or more directories after the options");
            return report.finish(EXIT_CONTEXT);
        }
        for (RootConfiguration rc : groups) {
            if (!Pattern.compile(Main.ACCEPTABLE_SOURCE_LEVEL_PATTERN).matcher(rc.sourceLevel).matches()) {
                report.diagnostic("error", "JACKPOT_SOURCE_LEVEL", null, "unrecognized --source level '" + rc.sourceLevel + "'; use e.g. 17 or 21");
                return report.finish(EXIT_USAGE);
            }
        }

        Rules rules;
        if (parsed.has(inspectionOpt)) {
            if (parsed.has(rulesOpt) || parsed.has(ruleOpt)) {
                report.diagnostic("error", "JACKPOT_USAGE", null, "--inspection cannot be combined with --rules/--rule");
                return report.finish(EXIT_USAGE);
            }
            String name = parsed.valueOf(inspectionOpt);
            HintsSettings settings = HintsSettings.createPreferencesBasedHintsSettings(new Main.MemoryPreferences(), false, null);
            RootConfiguration first = groups.get(0);
            Iterable<? extends HintDescription> hints = Main.findHints(first.sourceCP, first.binaryCP, name, settings);
            if (!hints.iterator().hasNext()) {
                report.diagnostic("error", "JACKPOT_INSPECTION_UNKNOWN", null, "no built-in inspection named '" + name + "'; see `jackpot inspections`");
                return report.finish(EXIT_RULE_ERROR);
            }
            rules = new Rules("inspection:" + name, hints, settings, Collections.emptyList());
        } else {
            rules = readRules(parsed, rulesOpt, ruleOpt, report);
            if (rules == null) {
                return report.finish(EXIT_RULE_ERROR);
            }
        }
        report.put("rules", rules.describe());
        int unresolved = checkPatternResolution(rules, groups.get(0), report);
        if (unresolved > 0 && rewrite && !dryRun) {
            report.diagnostic("error", "JACKPOT_RULES_NOT_APPLIED", null, "nothing was written: " + unresolved + " name(s) in the rules cannot be resolved, so the rule set is not what you meant; fix them (or run with --dry-run to see what the other rules would do)");
            dryRun = true;
        }
        report.put("dryRun", dryRun);

        List<Match> matches = new ArrayList<>();
        List<Change> changes = new ArrayList<>();
        List<MessageImpl> problems = new LinkedList<>();
        int filesScanned = 0;

        for (RootConfiguration rc : groups) {
            if (rc.rootFolders.isEmpty()) continue;
            for (Folder f : rc.rootFolders) filesScanned += countJava(f.getFileObject());

            PatchDescription patch = null;
            Iterable<? extends HintDescription> hints = rules.descriptions;
            if (parsed.has(sinceDiff)) {
                patch = Main.createPatchDescription(rc, parsed.valueOf(sinceDiff));
                if (patch.file2AddedLines.isEmpty()) continue;
                hints = Main.filterHints(hints, patch);
            }

            BatchResult occurrences = runScan(rc, hints, rules.settings, patch, matches, problems);

            if (rewrite && occurrences != null) {
                RootConfiguration prev = Main.currentRootConfiguration.get();
                Main.currentRootConfiguration.set(rc);
                try {
                    Collection<ModificationResult> results = BatchUtilities.applyFixes(occurrences, new ProgressHandleWrapper(1), new AtomicBoolean(), new ArrayList<RefactoringElementImplementation>(), null, true, problems);
                    for (ModificationResult mr : results) {
                        for (FileObject fo : mr.getModifiedFileObjects()) {
                            String before = fo.asText();
                            String after = mr.getResultingSource(fo);
                            Change c = new Change(fo, mr.getDifferences(fo).size(), UnifiedDiff.of(display(fo), before, after));
                            c.before = before;
                            c.after = after;
                            c.context = rc;
                            changes.add(c);
                        }
                        if (!dryRun) {
                            mr.commit();
                            for (FileObject fo : mr.getModifiedFileObjects()) {
                                save(fo);
                                for (Change c : changes) if (c.file == fo) c.written = true;
                            }
                        }
                    }
                } finally {
                    Main.currentRootConfiguration.set(prev);
                }
            }
        }

        boolean incomplete = engineProblems(problems, report);

        int introducedErrors = 0;
        if (rewrite && !parsed.has("no-verify")) {
            for (Change c : changes) {
                introducedErrors += Verifier.introducedErrors(c, report);
            }
        }

        matches.sort(Comparator.comparing((Match m) -> m.file).thenComparingInt(m -> m.startOffset));
        report.put("matches", matchesToJson(matches));

        if (!diffOnly) {
            for (Match m : matches) {
                report.human(m.file + ":" + m.startLine + ":" + m.startColumn + ": match [" + m.rule + "] " + m.description);
                report.human(m.line);
                report.human(m.caret());
            }
        }

        if (rewrite) {
            List<Object> files = new ArrayList<>();
            StringBuilder diff = new StringBuilder();
            int added = 0, removed = 0;
            for (Change c : changes) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("path", display(c.file));
                f.put("linesAdded", c.linesAdded);
                f.put("linesRemoved", c.linesRemoved);
                f.put("textEdits", c.textEdits);
                f.put("written", c.written);
                files.add(f);
                diff.append(c.diff);
                added += c.linesAdded;
                removed += c.linesRemoved;
            }
            report.put("files", files);
            report.put("diff", diff.toString());
            if (!diffOnly && !matches.isEmpty()) report.human("");
            report.human(diff.toString().trim());
            if (!diffOnly) {
                report.human("");
                report.human((dryRun ? "would change " : "changed ") + changes.size() + " file(s) (+" + added + "/-" + removed + " lines) from " + matches.size() + " match(es) in " + filesScanned + " file(s)"
                        + (incomplete ? " - INCOMPLETE, see diagnostics" : "")
                        + (introducedErrors > 0 ? " - " + introducedErrors + " NEW COMPILE ERROR(S), see diagnostics" : ""));
            }
            report.summary("filesScanned", filesScanned);
            report.summary("matches", matches.size());
            report.summary("filesChanged", changes.size());
            report.summary("linesAdded", added);
            report.summary("linesRemoved", removed);
            if (!parsed.has("no-verify")) {
                report.summary("introducedErrors", introducedErrors);
            }
            report.summary("written", dryRun ? 0 : changes.size());
            if (incomplete) {
                return report.finish(EXIT_REWRITE_FAILED);
            }
            if (!matches.isEmpty() && changes.isEmpty() && rules.hasFixes()) {
                report.diagnostic("warning", "JACKPOT_NO_CHANGES", null, "rules matched but produced no changes; the matched rules may be queries (no '=>')");
            }
        } else {
            report.human((matches.isEmpty() ? "no matches" : matches.size() + " match(es)") + " in " + filesScanned + " file(s)");
            report.summary("filesScanned", filesScanned);
            report.summary("matches", matches.size());
        }

        if (matches.isEmpty() && unresolved == 0) {
            report.diagnostic("info", "JACKPOT_NO_MATCHES", null, "no matches: if the code clearly contains the pattern, check that --source matches the tree and that every type the rule names is on --classpath (unresolvable types match nothing); `jackpot try` tests a rule against a snippet");
        } else if (matches.isEmpty()) {
            report.diagnostic("info", "JACKPOT_NO_MATCHES", null, "no matches - see JACKPOT_PATTERN_UNRESOLVED above");
        }

        if (unresolved > 0) {
            return report.finish(EXIT_RULE_ERROR);
        }
        if (!rewrite && parsed.has("fail-on-match") && !matches.isEmpty()) {
            return report.finish(EXIT_MATCHES_FOUND);
        }
        return report.finish(EXIT_OK);
    }

    // --- engine plumbing --------------------------------------------------------

    static final class NamedGroup {
        final String name;
        final RootConfiguration rc;
        NamedGroup(String name, RootConfiguration rc) { this.name = name; this.rc = rc; }
    }

    /** @return the contexts to process, or null when a build tool context was requested and could not be derived */
    private static List<NamedGroup> parseGroups(OptionSet parsed, Main.GroupOptions groupOptions, ArgumentAcceptingOptionSpec<String> group, BuildTool build, Report report) throws Exception {
        List<NamedGroup> groups = new ArrayList<>();
        for (Object sr : parsed.nonOptionArguments()) {
            if (FileUtil.toFileObject(new File(sr.toString())) == null) {
                report.diagnostic("error", "JACKPOT_ROOT_NOT_FOUND", null, "source root does not exist: " + sr);
            }
        }
        RootConfiguration main = new RootConfiguration(parsed, groupOptions);
        if (!main.rootFolders.isEmpty() || (build == null && !parsed.has(group))) {
            groups.add(new NamedGroup("command line", main));
        }
        int i = 1;
        for (String groupValue : parsed.valuesOf(group)) {
            OptionParser groupParser = new OptionParser();
            Main.GroupOptions go = Main.setupGroupParser(groupParser);
            groups.add(new NamedGroup("--group " + i++, new RootConfiguration(groupParser.parse(Main.splitGroupArg(groupValue)), go)));
        }
        if (build != null) {
            List<BuildContext.Group> derived = build.resolve(report);
            if (derived == null) return null;
            for (BuildContext.Group g : derived) {
                //an explicit --source on the command line overrides the build's
                if (parsed.has(groupOptions.source)) g.sourceLevel = parsed.valueOf(groupOptions.source);
                OptionParser groupParser = new OptionParser();
                Main.GroupOptions go = Main.setupGroupParser(groupParser);
                groups.add(new NamedGroup(build.kind + ":" + g.module, new RootConfiguration(groupParser.parse(Main.splitGroupArg(g.toGroupArg())), go)));
            }
        }
        return groups;
    }

    private static List<Object> contextJson(List<NamedGroup> groups) {
        List<Object> l = new ArrayList<>();
        for (NamedGroup ng : groups) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", ng.name);
            List<String> roots = new ArrayList<>();
            for (Folder f : ng.rc.rootFolders) roots.add(display(f.getFileObject()));
            m.put("roots", roots);
            m.put("source", ng.rc.sourceLevel);
            m.put("classpathEntries", ng.rc.compileCP.entries().size());
            l.add(m);
        }
        return l;
    }

    /** A requested build-tool context (--maven / --gradle). */
    static final class BuildTool {
        final String kind;
        final Path dir;

        private BuildTool(String kind, Path dir) { this.kind = kind; this.dir = dir; }

        static BuildTool from(OptionSet parsed, ArgumentAcceptingOptionSpec<String> maven, ArgumentAcceptingOptionSpec<String> gradle) {
            if (parsed.has(maven)) return new BuildTool("maven", Paths.get(parsed.valueOf(maven) != null ? parsed.valueOf(maven) : ".").toAbsolutePath().normalize());
            if (parsed.has(gradle)) return new BuildTool("gradle", Paths.get(parsed.valueOf(gradle) != null ? parsed.valueOf(gradle) : ".").toAbsolutePath().normalize());
            return null;
        }

        List<BuildContext.Group> resolve(Report report) throws Exception {
            BuildContext.Diagnostics diag = (severity, code, message) -> report.diagnostic(severity, code, null, message);
            long start = System.nanoTime();
            List<BuildContext.Group> groups = "maven".equals(kind) ? BuildContext.maven(dir, diag) : BuildContext.gradle(dir, diag);
            report.put("buildTool", kind);
            report.put("buildToolMillis", (System.nanoTime() - start) / 1_000_000);
            return groups;
        }
    }

    private static int context(Report report, BuildTool build) throws Exception {
        if (build == null) {
            report.diagnostic("error", "JACKPOT_USAGE", null, "context needs --maven [dir] or --gradle [dir]");
            return report.finish(EXIT_USAGE);
        }
        List<BuildContext.Group> groups = build.resolve(report);
        if (groups == null) return report.finish(EXIT_CONTEXT);
        List<Object> json = new ArrayList<>();
        List<String> args = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            BuildContext.Group g = groups.get(i);
            json.add(g.toJson());
            if (i == 0) {
                args.addAll(g.toArgs());
            } else {
                args.add("--group");
                args.add(g.toGroupArg());
            }
        }
        report.put("groups", json);
        report.put("args", args);
        for (String a : args) report.human(a);
        report.summary("groups", groups.size());
        return report.finish(EXIT_OK);
    }

    /** Finds the occurrences for one group, records the verified matches, and returns the raw result for applying. */
    private static BatchResult runScan(RootConfiguration rc, Iterable<? extends HintDescription> hints, HintsSettings settings, PatchDescription patch, List<Match> matches, List<MessageImpl> problems) throws IOException {
        RootConfiguration prev = Main.currentRootConfiguration.get();
        Main.currentRootConfiguration.set(rc);
        try {
            BatchSearch.Scope scope = Scopes.specifiedFoldersScope(rc.rootFolders.toArray(new Folder[0]));
            BatchResult occurrences = BatchSearch.findOccurrences(hints, scope, new ProgressHandleWrapper(1), settings);
            occurrences = Main.filterBatchResult(occurrences, patch);
            Map<String, String> id2DisplayName = org.netbeans.modules.jackpot30.cmdline.lib.Utils.computeId2DisplayName(hints);
            BatchSearch.getVerifiedSpans(occurrences, new ProgressHandleWrapper(1), new VerifiedSpansCallBack() {
                @Override public void groupStarted() {}
                @Override public boolean spansVerified(CompilationController wc, Resource r, Collection<? extends ErrorDescription> eds) throws Exception {
                    String text = wc.getText();
                    for (ErrorDescription ed : eds) {
                        matches.add(new Match(ed, text, id2DisplayName));
                    }
                    return true;
                }
                @Override public void groupFinished() {}
                @Override public void cannotVerifySpan(Resource r) {
                    problems.add(new MessageImpl(MessageKind.WARNING, "cannot verify matches in " + display(r.getResolvedFile()) + " (the file may not compile)"));
                }
            }, true, problems, new AtomicBoolean());
            return occurrences;
        } finally {
            Main.currentRootConfiguration.set(prev);
        }
    }

    /** @return true when a problem means the result is incomplete (a file whose fixes failed keeps its original content) */
    private static boolean engineProblems(List<MessageImpl> problems, Report report) {
        boolean incomplete = false;
        for (MessageImpl p : problems) {
            //text produced by BatchSearch/BatchUtilities in spi.java.hints:
            if (p.text.startsWith("An exception occurred while processing file")) {
                report.diagnostic("error", "JACKPOT_FIX_FAILED", null, p.text + " - no changes were written to that file; rerun with --debug for the cause");
                incomplete = true;
            } else if (p.text.startsWith("More than one fix for")) {
                report.diagnostic("warning", "JACKPOT_MULTIPLE_FIXES", null, p.text + " - make the fix conditions mutually exclusive");
            } else {
                report.diagnostic(p.kind == MessageKind.ERROR ? "error" : "warning", "JACKPOT_ENGINE", null, p.text);
                incomplete |= p.kind == MessageKind.ERROR;
            }
        }
        return incomplete;
    }

    /** commit() writes into an open Document instead of the file; see Main.apply(). */
    private static void save(FileObject fo) throws IOException {
        try {
            org.netbeans.api.actions.Savable sc = DataObject.find(fo).getLookup().lookup(org.netbeans.api.actions.Savable.class);
            if (sc != null) {
                sc.save();
            }
        } catch (DataObjectNotFoundException ex) {
            //nothing to save
        }
    }

    // --- rules ------------------------------------------------------------------

    static final class Rules {
        final String origin;
        final String text;
        final Iterable<? extends HintDescription> descriptions;
        final HintsSettings settings;
        final List<Map<String, Object>> parsedRules;
        DeclarativeHintsParser.Result parsed;

        Rules(String origin, Iterable<? extends HintDescription> descriptions, HintsSettings settings, List<Map<String, Object>> parsedRules) {
            this(origin, null, descriptions, settings, parsedRules);
        }

        Rules(String origin, String text, Iterable<? extends HintDescription> descriptions, HintsSettings settings, List<Map<String, Object>> parsedRules) {
            this.origin = origin;
            this.text = text;
            this.descriptions = descriptions;
            this.settings = settings;
            this.parsedRules = parsedRules;
        }

        boolean hasFixes() {
            for (Map<String, Object> r : parsedRules) {
                if (!Integer.valueOf(0).equals(r.get("fixes"))) return true;
            }
            return parsedRules.isEmpty();
        }

        List<Object> describe() {
            List<Object> l = new ArrayList<>();
            if (parsedRules.isEmpty()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("source", origin);
                l.add(m);
            }
            for (Map<String, Object> r : parsedRules) {
                Map<String, Object> m = new LinkedHashMap<>(r);
                m.put("source", origin);
                l.add(m);
            }
            return l;
        }
    }

    private static Rules readRules(OptionSet parsed, ArgumentAcceptingOptionSpec<String> rulesOpt, ArgumentAcceptingOptionSpec<String> ruleOpt, Report report) throws IOException {
        StringBuilder text = new StringBuilder();
        List<String> origins = new ArrayList<>();
        if (parsed.has(rulesOpt)) {
            String f = parsed.valueOf(rulesOpt);
            if ("-".equals(f)) {
                text.append(readAll(System.in));
                origins.add("stdin");
            } else {
                Path p = Paths.get(f);
                if (!Files.isRegularFile(p)) {
                    report.diagnostic("error", "JACKPOT_RULES_NOT_FOUND", null, "rule file does not exist: " + f);
                    return null;
                }
                text.append(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
                origins.add(f);
            }
        }
        for (String r : parsed.valuesOf(ruleOpt)) {
            if (text.length() > 0 && text.charAt(text.length() - 1) != '\n') text.append('\n');
            text.append(r).append('\n');
            origins.add("--rule");
        }
        if (text.toString().trim().isEmpty()) {
            report.diagnostic("error", "JACKPOT_NO_RULES", null, "no rules given: use --rules <file>, --rules - (stdin), --rule '<text>' or --inspection <name>");
            return null;
        }
        String rules = text.toString();
        String origin = String.join(",", origins);

        //<? ... ?> blocks: a leading block of nothing but import statements only widens
        //name resolution for the patterns and is fine; anything else is Java that would
        //run in-process and is refused unless --allow-embedded-java is given.
        boolean onlyImports = true;
        java.util.regex.Matcher blocks = Pattern.compile("(?s)<\\?(.*?)\\?>").matcher(rules);
        boolean first = true;
        while (blocks.find()) {
            String body = blocks.group(1).trim();
            boolean importsOnly = first && !body.isEmpty() && body.matches("(?s)(import\\s+(static\\s+)?[\\w.]+(\\.\\*)?\\s*;\\s*)+")
                    && rules.substring(0, blocks.start()).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "").trim().isEmpty();
            if (!importsOnly) onlyImports = false;
            first = false;
        }
        DeclarativeHintsParser.disableCustomCode = !parsed.has("allow-embedded-java") && !onlyImports;

        //the parser attaches its errors to a FileObject:
        FileObject rulesFile = FileUtil.createMemoryFileSystem().getRoot().createData("rules", "hint");
        try (java.io.OutputStream os = rulesFile.getOutputStream()) {
            os.write(rules.getBytes(StandardCharsets.UTF_8));
        }

        TokenHierarchy<?> h = TokenHierarchy.create(rules, DeclarativeHintTokenId.language());
        TokenSequence<DeclarativeHintTokenId> ts = h.tokenSequence(DeclarativeHintTokenId.language());
        DeclarativeHintsParser.Result result = new DeclarativeHintsParser().parse(rulesFile, rules, ts);
        LineTable lines = new LineTable(rules);
        boolean errors = false;

        String withoutComments = rules.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\n]*", " ").replaceAll("(?s)<\\?.*?\\?>", " ").trim();
        if (!withoutComments.isEmpty() && !withoutComments.endsWith(";;")) {
            int off = rules.length() - 1;
            report.diagnostic("error", "JACKPOT_RULE_UNTERMINATED", origin + ":" + lines.line(off), "the last rule is not terminated: every rule must end with ';;'");
            errors = true;
        }
        //constructs that parse but do not do what they look like they do:
        String ruleCode = stripCommentsKeepOffsets(rules);
        java.util.regex.Matcher brace = Pattern.compile("\\$[A-Za-z_][A-Za-z0-9_]*\\{[A-Za-z_][A-Za-z0-9_.]*\\}").matcher(ruleCode);
        while (brace.find()) {
            report.diagnostic("error", "JACKPOT_RULE_BRACE_TYPE", origin + ":" + lines.line(brace.start()) + ":" + lines.column(brace.start()), "'$x{Type}' is a legacy form with different semantics; write ':: $x instanceof fully.qualified.Type' instead");
            errors = true;
        }
        //only inside condition sections ('::' up to the next '=>' or ';;'); '||' is legitimate Java in a pattern
        java.util.regex.Matcher cond = Pattern.compile("(?s)::((?:(?!=>|;;).)*)").matcher(ruleCode);
        while (cond.find()) {
            String section = cond.group(1);
            int base = cond.start(1);
            for (String[] footgun : new String[][] {
                    {"\\|\\|", "JACKPOT_RULE_OR", "'||' is not supported in conditions (only '&&' and '!'); write one rule per alternative"},
                    {"(?<![A-Za-z0-9_.$])(matches|parentMatches)\\s*\\(", "JACKPOT_RULE_UNSUPPORTED_CONDITION", "'matches(...)' and 'parentMatches(...)' do not work in this engine; use 'matchesAny($x, \"pattern\")' on a bound variable"},
            }) {
                java.util.regex.Matcher m = Pattern.compile(footgun[0]).matcher(section);
                while (m.find()) {
                    int off = base + m.start();
                    report.diagnostic("error", footgun[1], origin + ":" + lines.line(off) + ":" + lines.column(off), footgun[2]);
                    errors = true;
                }
            }
        }
        for (ErrorDescription ed : result.errors) {
            int off = ed.getRange().getBegin().getOffset();
            String description = ed.getDescription();
            String code = "JACKPOT_RULE_ERROR";
            if (description.contains("Custom code not allowed")) {
                code = "JACKPOT_RULE_EMBEDDED_JAVA";
                description += "; embedded Java runs in-process and is refused by default (--allow-embedded-java to accept)";
            } else if (description.contains("Cannot resolve method")) {
                description = "unknown condition function; supported: instanceof, referencedIn, matchesAny, containsAny, matchesWithBind, hasModifier, elementKindMatches, inClass, inPackage, isNullLiteral, isAvailable, sourceVersionGE/LE";
            }
            report.diagnostic("error", code, origin + ":" + lines.line(off) + ":" + lines.column(off), description);
            errors = true;
        }
        if (errors) {
            return null;
        }
        if (result.hints.isEmpty()) {
            report.diagnostic("error", "JACKPOT_RULE_ERROR", origin, "no rules parsed; each rule must end with ';;'");
            return null;
        }

        Iterable<? extends HintDescription> descriptions = PatternConvertor.create(rules);
        if (descriptions == null || !descriptions.iterator().hasNext()) {
            report.diagnostic("error", "JACKPOT_RULE_ERROR", origin, "the rules could not be converted into patterns");
            return null;
        }
        HintsSettings settings = HintsSettings.createPreferencesBasedHintsSettings(new Main.MemoryPreferences(), false, null);
        for (HintDescription hd : descriptions) {
            settings.setEnabled(hd.getMetadata(), true);
        }

        List<Map<String, Object>> parsedRules = new ArrayList<>();
        int i = 0;
        for (DeclarativeHintsParser.HintTextDescription htd : result.hints) {
            Map<String, Object> m = new LinkedHashMap<>();
            String displayName = htd.displayName;
            m.put("index", i++);
            m.put("name", displayName);
            m.put("id", displayName != null ? displayName.replaceAll("[^A-Za-z0-9]", "_") : "TODO_No_display_name");
            m.put("line", lines.line(htd.textStart));
            m.put("fixes", htd.fixes.size());
            m.put("conditions", htd.conditions.size());
            parsedRules.add(m);
        }
        Rules result2 = new Rules(origin, rules, descriptions, settings, parsedRules);
        result2.parsed = result;
        return result2;
    }

    /**
     * Attributes every rule's pattern the way the engine will (scratch scope with
     * the rule file's imports and the {@code instanceof} constraints) and reports
     * names javac cannot resolve. Such a pattern can never match: patterns are
     * resolved without the scanned sources' imports, so a bare {@code List} or an
     * implicit-this method call resolves to nothing. This is what the IDE's rule
     * editor underlines ({@code idebinding.HintsTask}); the batch tool never ran it.
     *
     * @return number of unresolved names reported
     */
    static int checkPatternResolution(Rules rules, RootConfiguration rc, Report report) {
        if (rules.parsed == null || rules.text == null) return 0;
        int[] count = {0};
        Path tmpRoot = null;
        try {
            tmpRoot = Files.createTempDirectory("jackpot-rules");
            Path scratch = tmpRoot.resolve("Scratch.java");
            Files.write(scratch, "class Scratch {}".getBytes(StandardCharsets.UTF_8));
            FileObject scratchFO = FileUtil.toFileObject(FileUtil.normalizeFile(scratch.toFile()));
            org.netbeans.api.java.source.ClasspathInfo cpInfo = org.netbeans.api.java.source.ClasspathInfo.create(rc.bootCP, rc.compileCP, rc.sourceCP);
            org.netbeans.api.java.source.JavaSource js = org.netbeans.api.java.source.JavaSource.create(cpInfo, scratchFO);
            if (js == null) return 0;
            LineTable lines = new LineTable(rules.text);
            DeclarativeHintsParser.Result parsed = rules.parsed;
            List<String> imports = parsed.importsBlock != null
                    ? Collections.singletonList(rules.text.substring(parsed.importsBlock[0], parsed.importsBlock[1]))
                    : Collections.emptyList();
            RootConfiguration prev = Main.currentRootConfiguration.get();
            Main.currentRootConfiguration.set(rc);
            try {
                js.runUserActionTask(cc -> {
                    cc.toPhase(org.netbeans.api.java.source.JavaSource.Phase.RESOLVED);
                    for (DeclarativeHintsParser.HintTextDescription hd : parsed.hints) {
                        String code = rules.text.substring(hd.textStart, hd.textEnd);
                        Map<String, javax.lang.model.type.TypeMirror> constraints = new LinkedHashMap<>();
                        for (Entry<String, String> e : org.netbeans.modules.java.hints.declarative.Utilities.conditions2Constraints(hd.conditions).entrySet()) {
                            javax.lang.model.type.TypeMirror t = org.netbeans.modules.java.hints.declarative.Hacks.parseFQNType(cc, e.getValue());
                            boolean resolvable = t != null && t.getKind() != javax.lang.model.type.TypeKind.ERROR;
                            if (!resolvable && !imports.isEmpty()) {
                                //a simple name made visible by the rule file's imports block
                                Collection<javax.tools.Diagnostic<? extends javax.tools.JavaFileObject>> probe = new ArrayList<>();
                                com.sun.source.tree.Scope importScope = org.netbeans.modules.java.hints.spiimpl.Utilities.constructScope(cc, Collections.emptyMap(), imports);
                                org.netbeans.modules.java.hints.spiimpl.Utilities.parseAndAttribute(cc, "(" + e.getValue() + ") null", importScope, probe);
                                resolvable = probe.stream().noneMatch(d -> d.getKind() == javax.tools.Diagnostic.Kind.ERROR);
                            }
                            if (resolvable) {
                                if (t != null && t.getKind() != javax.lang.model.type.TypeKind.ERROR) constraints.put(e.getKey(), t);
                            } else {
                                //the engine silently drops a constraint it cannot resolve, and the rule never matches
                                report.diagnostic("error", "JACKPOT_PATTERN_UNRESOLVED", rules.origin + ":" + lines.line(hd.textStart),
                                        "type " + e.getValue() + " in the condition on " + e.getKey() + " cannot be resolved - the rule cannot match; if it is a library type, add its jar to --classpath (or use --maven/--gradle); if it is a JDK type, check the spelling and the fully qualified name");
                                count[0]++;
                            }
                        }
                        Collection<javax.tools.Diagnostic<? extends javax.tools.JavaFileObject>> errors = new ArrayList<>();
                        com.sun.source.tree.Scope scope = org.netbeans.modules.java.hints.spiimpl.Utilities.constructScope(cc, constraints, imports);
                        org.netbeans.modules.java.hints.spiimpl.Utilities.parseAndAttribute(cc, code, scope, errors);
                        for (javax.tools.Diagnostic<? extends javax.tools.JavaFileObject> d : errors) {
                            if (d.getKind() != javax.tools.Diagnostic.Kind.ERROR) continue;
                            String message = d.getMessage(java.util.Locale.ENGLISH).replace("\n", " ").replaceAll("\\s+", " ").trim();
                            //pattern variables ($x, $stmts$) are placeholders, not unresolved names; the engine's
                            //own filter for them (Utilities.parseAndAttribute) misses javac's aligned
                            //"symbol:   variable $x" formatting
                            java.util.regex.Matcher sym = Pattern.compile("symbol: (?:\\w+ )?(\\S+)").matcher(message);
                            if (sym.find() && sym.group(1).startsWith("$")) continue;
                            //the scratch scope's own class shows up as the location; drop that noise from the message
                            message = message.replaceAll(" location: class \\$\\$\\.\\S+", "");
                            int off = hd.textStart + (int) Math.max(0, d.getStartPosition());
                            report.diagnostic("error", "JACKPOT_PATTERN_UNRESOLVED", rules.origin + ":" + lines.line(off) + ":" + lines.column(off),
                                    message + " - the pattern cannot match: names in a pattern are resolved without the sources' imports; use the fully qualified name (java.util.List), bind the receiver with a $variable, or add an <?import ...?> block to the rules; a library type also needs its jar on --classpath");
                            count[0]++;
                        }
                    }
                }, true);
            } finally {
                Main.currentRootConfiguration.set(prev);
            }
        } catch (IOException | RuntimeException ex) {
            report.diagnostic("warning", "JACKPOT_VERIFY_FAILED", rules.origin, "could not check the patterns for unresolved names: " + ex);
        } finally {
            if (tmpRoot != null) deleteRecursively(tmpRoot);
        }
        return count[0];
    }

    /** Blanks comments and string literals, keeping every other character at its offset. */
    static String stripCommentsKeepOffsets(String text) {
        StringBuilder sb = new StringBuilder(text);
        java.util.regex.Matcher m = Pattern.compile("(?s)/\\*.*?\\*/|//[^\n]*|\"(?:[^\"\\\\\n]|\\\\.)*\"").matcher(text);
        while (m.find()) {
            for (int i = m.start(); i < m.end(); i++) {
                if (sb.charAt(i) != '\n') sb.setCharAt(i, ' ');
            }
        }
        return sb.toString();
    }

    /** Spans of the individual {@code &&}-separated conditions after the first {@code ::} of the (single) rule. */
    static List<int[]> conditionSpans(String rule) {
        List<int[]> spans = new ArrayList<>();
        String code = stripCommentsKeepOffsets(rule);
        java.util.regex.Matcher m = Pattern.compile("(?s)::((?:(?!=>|;;).)*)").matcher(code);
        if (!m.find()) return spans;
        int base = m.start(1);
        String section = m.group(1);
        int depth = 0, start = 0;
        for (int i = 0; i < section.length(); i++) {
            char c = section.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (depth == 0 && c == '&' && i + 1 < section.length() && section.charAt(i + 1) == '&') {
                spans.add(new int[] {base + start, base + i});
                start = i + 2;
                i++;
            }
        }
        spans.add(new int[] {base + start, base + section.length()});
        return spans;
    }

    /** Removes {@code :: conditions} (pattern and fix conditions) from the rule text, for diagnosing a non-match. */
    static String stripConditions(String rules) {
        return rules.replaceAll("(?s)::\\s*(?:(?!=>|;;).)*", "");
    }

    static String wrapSnippet(String snippet) {
        String s = snippet.trim();
        if (s.contains("class ") || s.contains("interface ") || s.contains("enum ") || s.contains("record ")) {
            return s.endsWith("\n") ? s : s + "\n";
        }
        boolean memberLike = s.matches("(?s)^(public|private|protected|static|final|abstract|synchronized|native|<|[A-Za-z_$][\\w$<>\\[\\],.?\\s]*\\s+[A-Za-z_$][\\w$]*\\s*\\().*\\}\\s*$");
        if (memberLike) {
            return "class Try {\n" + s + "\n}\n";
        }
        if (!s.endsWith(";") && !s.endsWith("}")) {
            s = s + ";";
        }
        return "import java.util.*;\nimport java.util.function.*;\nimport java.io.*;\n\nclass Try {\n    void snippet() throws Exception {\n        " + s.replace("\n", "\n        ") + "\n    }\n}\n";
    }

    // --- model ------------------------------------------------------------------

    static final class Match {
        final String rule;
        final String description;
        final String file;
        final int startOffset;
        final int startLine, startColumn, endLine, endColumn;
        final String text;
        final String line;
        final int fixes;

        Match(ErrorDescription ed, String fileText, Map<String, String> id2DisplayName) {
            String idDisplayName = org.netbeans.modules.jackpot30.cmdline.lib.Utils.categoryName(ed.getId(), id2DisplayName);
            this.rule = idDisplayName.replaceAll("^\\[|\\] *$", "").trim();
            this.description = ed.getDescription();
            this.file = display(ed.getFile());
            int b = ed.getRange().getBegin().getOffset();
            int e = ed.getRange().getEnd().getOffset();
            b = Math.max(0, Math.min(b, fileText.length()));
            e = Math.max(b, Math.min(e, fileText.length()));
            LineTable lt = new LineTable(fileText);
            this.startOffset = b;
            this.startLine = lt.line(b);
            this.startColumn = lt.column(b);
            this.endLine = lt.line(e);
            this.endColumn = lt.column(e);
            this.text = fileText.substring(b, e);
            this.line = lt.lineText(b);
            this.fixes = ed.getFixes().isComputed() ? ed.getFixes().getFixes().size() : -1;
        }

        String caret() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < startColumn - 1 && i < line.length(); i++) {
                sb.append(Character.isWhitespace(line.charAt(i)) ? line.charAt(i) : ' ');
            }
            return sb.append('^').toString();
        }

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rule", rule);
            m.put("file", file);
            Map<String, Object> range = new LinkedHashMap<>();
            range.put("start", Arrays.asList(startLine, startColumn));
            range.put("end", Arrays.asList(endLine, endColumn));
            m.put("range", range);
            m.put("text", text);
            m.put("description", description);
            if (fixes >= 0) m.put("fixes", fixes);
            return m;
        }
    }

    private static List<Object> matchesToJson(List<Match> matches) {
        List<Object> l = new ArrayList<>();
        for (Match m : matches) l.add(m.toJson());
        return l;
    }

    static final class Change {
        final FileObject file;
        final int textEdits;
        final String diff;
        final int linesAdded, linesRemoved;
        boolean written;
        String before, after;
        RootConfiguration context;

        Change(FileObject file, int textEdits, String diff) {
            this.file = file;
            this.textEdits = textEdits;
            this.diff = diff;
            int added = 0, removed = 0;
            for (String line : diff.split("\n")) {
                if (line.startsWith("+") && !line.startsWith("+++")) added++;
                else if (line.startsWith("-") && !line.startsWith("---")) removed++;
            }
            this.linesAdded = added;
            this.linesRemoved = removed;
        }
    }

    /** 1-based line/column lookup over a text. */
    static final class LineTable {
        private final int[] starts;
        private final String text;

        LineTable(String text) {
            this.text = text;
            List<Integer> s = new ArrayList<>();
            s.add(0);
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') s.add(i + 1);
            }
            starts = new int[s.size()];
            for (int i = 0; i < starts.length; i++) starts[i] = s.get(i);
        }

        int lineIndex(int offset) {
            int lo = 0, hi = starts.length - 1;
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (starts[mid] <= offset) lo = mid; else hi = mid - 1;
            }
            return lo;
        }

        int line(int offset) { return lineIndex(offset) + 1; }
        int column(int offset) { return offset - starts[lineIndex(offset)] + 1; }

        String lineText(int offset) {
            int li = lineIndex(offset);
            int end = li + 1 < starts.length ? starts[li + 1] - 1 : text.length();
            if (end > 0 && end <= text.length() && end > starts[li] && text.charAt(end - 1) == '\r') end--;
            return text.substring(starts[li], Math.max(starts[li], end));
        }
    }

    // --- verification -----------------------------------------------------------------

    /**
     * Re-attributes a changed file and reports the compile errors that the
     * rewrite introduced (errors already present in the original are ignored).
     * The engine does not type-check replacements, so a replacement can refer to
     * a method the receiver does not have; this makes that visible.
     */
    @SuppressWarnings("rawtypes") //CompilationInfo.getDiagnostics() returns the raw List<Diagnostic>
    static final class Verifier {

        static int introducedErrors(Change c, Report report) {
            try {
                Set<String> before = errors(c.context, c.file, c.before);
                List<javax.tools.Diagnostic> after = diagnostics(c.context, c.file, c.after);
                LineTable lines = new LineTable(c.after);
                int count = 0;
                for (javax.tools.Diagnostic d : after) {
                    if (d.getKind() != javax.tools.Diagnostic.Kind.ERROR) continue;
                    String message = d.getMessage(null);
                    if (before.contains(message)) continue;
                    int off = (int) Math.max(0, Math.min(d.getStartPosition(), c.after.length()));
                    report.diagnostic("error", "JACKPOT_INTRODUCED_ERROR", display(c.file) + ":" + lines.line(off) + ":" + lines.column(off), message + " - the rewritten code does not compile; the replacement is wrong for this receiver, or the project needs a different --source/--classpath");
                    count++;
                }
                return count;
            } catch (IOException | RuntimeException ex) {
                report.diagnostic("warning", "JACKPOT_VERIFY_FAILED", display(c.file), "could not re-compile the changed file to verify it: " + ex);
                return 0;
            }
        }

        private static Set<String> errors(RootConfiguration rc, FileObject original, String text) throws IOException {
            Set<String> s = new java.util.HashSet<>();
            for (javax.tools.Diagnostic d : diagnostics(rc, original, text)) {
                if (d.getKind() == javax.tools.Diagnostic.Kind.ERROR) s.add(d.getMessage(null));
            }
            return s;
        }

        /** Attributes {@code text} as if it were {@code original} (same package path), against the group's classpaths. */
        private static List<javax.tools.Diagnostic> diagnostics(RootConfiguration rc, FileObject original, String text) throws IOException {
            FileObject root = rc.sourceCP.findOwnerRoot(original);
            String relative = root != null ? FileUtil.getRelativePath(root, original) : original.getNameExt();
            //a shadow copy on disk (JavaSource needs a file: URL) at the same package path;
            //sibling classes still resolve through the real source path
            Path tmpRoot = Files.createTempDirectory("jackpot-verify");
            try {
                Path copyPath = tmpRoot.resolve(relative);
                Files.createDirectories(copyPath.getParent());
                Files.write(copyPath, text.getBytes(StandardCharsets.UTF_8));
                FileObject tmpRootFO = FileUtil.toFileObject(FileUtil.normalizeFile(tmpRoot.toFile()));
                FileObject copy = FileUtil.toFileObject(FileUtil.normalizeFile(copyPath.toFile()));
                ClassPath sources = org.netbeans.spi.java.classpath.support.ClassPathSupport.createProxyClassPath(
                        org.netbeans.spi.java.classpath.support.ClassPathSupport.createClassPath(tmpRootFO), rc.sourceCP);
                org.netbeans.api.java.source.ClasspathInfo cpInfo = org.netbeans.api.java.source.ClasspathInfo.create(rc.bootCP, rc.compileCP, sources);
                org.netbeans.api.java.source.JavaSource js = org.netbeans.api.java.source.JavaSource.create(cpInfo, copy);
                List<javax.tools.Diagnostic> result = new ArrayList<>();
                if (js == null) return result;
                RootConfiguration prev = Main.currentRootConfiguration.get();
                Main.currentRootConfiguration.set(rc);
                try {
                    js.runUserActionTask(cc -> {
                        cc.toPhase(org.netbeans.api.java.source.JavaSource.Phase.RESOLVED);
                        result.addAll(cc.getDiagnostics());
                    }, true);
                } finally {
                    Main.currentRootConfiguration.set(prev);
                }
                return result;
            } finally {
                deleteRecursively(tmpRoot);
            }
        }
    }

    // --- output -------------------------------------------------------------------

    /** Collects the result; prints human-readable lines as they come unless JSON was requested, in which case one JSON document is printed at the end. */
    static final class Report {
        private final Map<String, Object> json = new LinkedHashMap<>();
        private final List<Object> diagnostics = new ArrayList<>();
        private final Map<String, Object> summary = new LinkedHashMap<>();
        private final PrintStream jsonOut;

        Report(String command, PrintStream jsonOut) {
            this.jsonOut = jsonOut;
            json.put("tool", "jackpot");
            json.put("version", TOOL_VERSION);
            json.put("command", command);
        }

        void put(String key, Object value) { json.put(key, value); }
        void summary(String key, Object value) { summary.put(key, value); }

        void human(String line) {
            if (jsonOut == null) System.out.println(line);
        }

        void diagnostic(String severity, String code, String location, String message) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("severity", severity);
            d.put("code", code);
            if (location != null) d.put("location", location);
            d.put("message", message);
            diagnostics.add(d);
            System.err.println(severity + "[" + code + "]: " + (location != null ? location + ": " : "") + message);
        }

        int finish(int exitCode) {
            if (jsonOut != null) {
                json.put("diagnostics", diagnostics);
                json.put("summary", summary);
                json.put("exitCode", exitCode);
                json.put("status", exitCode == EXIT_OK ? "ok" : exitCode == EXIT_MATCHES_FOUND ? "matches" : exitCode == EXIT_REWRITE_FAILED ? "incomplete" : "error");
                jsonOut.println(Json.write(json));
                jsonOut.flush();
            }
            return exitCode;
        }
    }

    /** Minimal JSON writer for maps, lists, strings, numbers, booleans and null. */
    static final class Json {
        static String write(Object o) {
            StringBuilder sb = new StringBuilder();
            write(o, sb, 0);
            return sb.toString();
        }

        private static void write(Object o, StringBuilder sb, int indent) {
            if (o == null) { sb.append("null"); }
            else if (o instanceof String) { quote((String) o, sb); }
            else if (o instanceof Number || o instanceof Boolean) { sb.append(o); }
            else if (o instanceof Map) {
                Map<?, ?> m = (Map<?, ?>) o;
                if (m.isEmpty()) { sb.append("{}"); return; }
                sb.append("{\n");
                int i = 0;
                for (Entry<?, ?> e : m.entrySet()) {
                    pad(sb, indent + 1);
                    quote(String.valueOf(e.getKey()), sb);
                    sb.append(": ");
                    write(e.getValue(), sb, indent + 1);
                    sb.append(++i < m.size() ? ",\n" : "\n");
                }
                pad(sb, indent).append('}');
            } else if (o instanceof Collection) {
                Collection<?> c = (Collection<?>) o;
                if (c.isEmpty()) { sb.append("[]"); return; }
                sb.append("[\n");
                int i = 0;
                for (Object e : c) {
                    pad(sb, indent + 1);
                    write(e, sb, indent + 1);
                    sb.append(++i < c.size() ? ",\n" : "\n");
                }
                pad(sb, indent).append(']');
            } else {
                quote(String.valueOf(o), sb);
            }
        }

        private static StringBuilder pad(StringBuilder sb, int indent) {
            for (int i = 0; i < indent; i++) sb.append("  ");
            return sb;
        }

        private static void quote(String s, StringBuilder sb) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
                }
            }
            sb.append('"');
        }
    }

    /** Line-based unified diff of two texts. */
    static final class UnifiedDiff {
        static String of(String path, String before, String after) {
            List<String> a = lines(before), b = lines(after);
            int prefix = 0;
            while (prefix < a.size() && prefix < b.size() && a.get(prefix).equals(b.get(prefix))) prefix++;
            int suffix = 0;
            while (suffix < a.size() - prefix && suffix < b.size() - prefix && a.get(a.size() - 1 - suffix).equals(b.get(b.size() - 1 - suffix))) suffix++;
            List<String> am = a.subList(prefix, a.size() - suffix), bm = b.subList(prefix, b.size() - suffix);
            List<String[]> ops = new ArrayList<>(); // {" "|"-"|"+", text}
            if (am.size() * (long) bm.size() <= 4_000_000L) {
                int[][] lcs = new int[am.size() + 1][bm.size() + 1];
                for (int i = am.size() - 1; i >= 0; i--)
                    for (int j = bm.size() - 1; j >= 0; j--)
                        lcs[i][j] = am.get(i).equals(bm.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
                int i = 0, j = 0;
                while (i < am.size() && j < bm.size()) {
                    if (am.get(i).equals(bm.get(j))) { ops.add(new String[] {" ", am.get(i)}); i++; j++; }
                    else if (lcs[i + 1][j] >= lcs[i][j + 1]) { ops.add(new String[] {"-", am.get(i++)}); }
                    else { ops.add(new String[] {"+", bm.get(j++)}); }
                }
                while (i < am.size()) ops.add(new String[] {"-", am.get(i++)});
                while (j < bm.size()) ops.add(new String[] {"+", bm.get(j++)});
            } else {
                for (String s : am) ops.add(new String[] {"-", s});
                for (String s : bm) ops.add(new String[] {"+", s});
            }
            if (ops.isEmpty()) return "";

            final int ctx = 3;
            //re-attach up to ctx lines of the trimmed common prefix/suffix as context
            int lead = Math.min(ctx, prefix);
            for (int i = prefix - lead; i < prefix; i++) ops.add(i - (prefix - lead), new String[] {" ", a.get(i)});
            int trail = Math.min(ctx, suffix);
            for (int i = a.size() - suffix; i < a.size() - suffix + trail; i++) ops.add(new String[] {" ", a.get(i)});

            StringBuilder out = new StringBuilder();
            out.append("--- a/").append(path).append('\n');
            out.append("+++ b/").append(path).append('\n');
            //hunks: split ops where there are > 2*ctx consecutive context lines
            int oldLine = prefix - lead + 1, newLine = prefix - lead + 1;
            int k = 0;
            while (k < ops.size()) {
                //skip leading context beyond ctx
                int firstChange = k;
                while (firstChange < ops.size() && ops.get(firstChange)[0].equals(" ")) firstChange++;
                if (firstChange == ops.size()) break;
                int hunkStart = Math.max(k, firstChange - ctx);
                //advance counters for skipped context
                for (int s = k; s < hunkStart; s++) { oldLine++; newLine++; }
                int hunkEnd = firstChange;
                int run = 0;
                int p = firstChange;
                while (p < ops.size()) {
                    if (ops.get(p)[0].equals(" ")) { run++; if (run > 2 * ctx) break; }
                    else { run = 0; hunkEnd = p + 1; }
                    p++;
                }
                int end = Math.min(ops.size(), hunkEnd + ctx);
                int oldCount = 0, newCount = 0;
                StringBuilder body = new StringBuilder();
                for (int s = hunkStart; s < end; s++) {
                    String[] op = ops.get(s);
                    body.append(op[0]).append(op[1]).append('\n');
                    if (!op[0].equals("+")) oldCount++;
                    if (!op[0].equals("-")) newCount++;
                }
                out.append("@@ -").append(oldLine).append(',').append(oldCount).append(" +").append(newLine).append(',').append(newCount).append(" @@\n");
                out.append(body);
                oldLine += oldCount;
                newLine += newCount;
                k = end;
            }
            return out.toString();
        }

        private static List<String> lines(String s) {
            List<String> l = new ArrayList<>(Arrays.asList(s.split("\n", -1)));
            if (!l.isEmpty() && l.get(l.size() - 1).isEmpty()) l.remove(l.size() - 1);
            return l;
        }
    }

    // --- utilities --------------------------------------------------------------------

    private static final Path CWD = Paths.get("").toAbsolutePath();

    static String display(FileObject fo) {
        File f = FileUtil.toFile(fo);
        return f != null ? display(f.toPath()) : fo.getPath();
    }

    static String display(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        return abs.startsWith(CWD) ? CWD.relativize(abs).toString() : abs.toString();
    }

    private static int countJava(FileObject root) {
        int n = 0;
        for (FileObject fo : org.openide.util.NbCollections.iterable(root.getChildren(true))) {
            if (fo.isData() && "java".equals(fo.getExt())) n++;
        }
        return n;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void deleteRecursively(Path p) {
        try {
            if (Files.isDirectory(p)) {
                try (java.util.stream.Stream<Path> s = Files.list(p)) {
                    s.forEach(AgentMain::deleteRecursively);
                }
            }
            Files.deleteIfExists(p);
        } catch (IOException ex) {
            Exceptions.printStackTrace(ex);
        }
    }
}
