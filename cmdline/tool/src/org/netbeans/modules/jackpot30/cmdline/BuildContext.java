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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Derives compilation contexts (source roots, source level, compile classpath)
 * from a Maven or Gradle project by asking the build tool, offline. Never
 * compiles, never downloads: the dependencies must already be in the local
 * repository/cache from the project's own build.
 */
final class BuildContext {

    /** One compilation context: what {@code --source/--classpath/--sourcepath <roots>} would express. */
    static final class Group {
        final String module;
        final List<Path> roots = new ArrayList<>();
        String sourceLevel;
        final List<Path> classpath = new ArrayList<>();

        Group(String module) {
            this.module = module;
        }

        /** The context as command-line flags, one per line, suitable for an {@code @file}. */
        List<String> toArgs() {
            List<String> args = new ArrayList<>();
            if (sourceLevel != null) {
                args.add("--source");
                args.add(sourceLevel);
            }
            if (!classpath.isEmpty()) {
                args.add("--classpath");
                args.add(join(classpath));
            }
            if (!roots.isEmpty()) {
                args.add("--sourcepath");
                args.add(join(roots));
                for (Path r : roots) args.add(r.toString());
            }
            return args;
        }

        /** The context as a single {@code --group} argument (space separated, spaces escaped). */
        String toGroupArg() {
            StringBuilder sb = new StringBuilder();
            for (String a : toArgs()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(a.replace("\\", "\\\\").replace(" ", "\\ "));
            }
            return sb.toString();
        }

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("module", module);
            m.put("roots", strings(roots));
            m.put("source", sourceLevel);
            m.put("classpath", strings(classpath));
            return m;
        }

        String classpathString() {
            return join(classpath);
        }

        private static String join(List<Path> paths) {
            StringBuilder sb = new StringBuilder();
            for (Path p : paths) {
                if (sb.length() > 0) sb.append(File.pathSeparatorChar);
                sb.append(p);
            }
            return sb.toString();
        }

        private static List<String> strings(List<Path> paths) {
            List<String> l = new ArrayList<>();
            for (Path p : paths) l.add(p.toString());
            return l;
        }
    }

    interface Diagnostics {
        void report(String severity, String code, String message);
    }

    // --- Maven ----------------------------------------------------------------------

    static List<Group> maven(Path dir, Diagnostics diag) throws IOException, InterruptedException {
        Path pom = dir.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            diag.report("error", "JACKPOT_MAVEN_NO_POM", "no pom.xml in " + dir);
            return null;
        }
        String mvn = findExecutable(dir, "mvnw", "mvn");
        if (mvn == null) {
            diag.report("error", "JACKPOT_MAVEN_NOT_FOUND", "neither ./mvnw nor mvn on PATH; Maven must be installed to resolve the classpath");
            return null;
        }

        //one reactor run; a relative outputFile is written per module
        String cpFile = "target/.jackpot-classpath";
        List<String> cmd = Arrays.asList(mvn, "-o", "-q", "-B", "dependency:build-classpath", "-DincludeScope=compile", "-Dmdep.outputFile=" + cpFile);
        Exec result = exec(cmd, dir);
        if (result.exitCode != 0) {
            String out = result.output.replaceAll("\u001b\\[[0-9;]*m", "").trim();
            String code = out.contains("Could not resolve dependencies") || out.contains("offline mode") || out.contains("Cannot access") ? "JACKPOT_MAVEN_OFFLINE" : "JACKPOT_MAVEN_FAILED";
            diag.report("error", code, "mvn dependency:build-classpath failed (exit " + result.exitCode + "); the dependencies (and the dependency plugin) must be in the local repository - run the project's build or `mvn dependency:resolve` once, or pass the context yourself: --source <level> --classpath <jars> <source-root>. Output:\n" + tail(out, 15));
            return null;
        }

        List<Group> groups = new ArrayList<>();
        for (Path moduleDir : mavenModules(pom, dir, diag)) {
            Path modulePom = moduleDir.resolve("pom.xml");
            Path cp = moduleDir.resolve(cpFile);
            Group g = new Group(dir.relativize(moduleDir).toString().isEmpty() ? "." : dir.relativize(moduleDir).toString());
            if (Files.isRegularFile(cp)) {
                String text = new String(Files.readAllBytes(cp), StandardCharsets.UTF_8).trim();
                if (!text.isEmpty()) {
                    for (String e : text.split(Pattern.quote(File.pathSeparator))) {
                        if (!e.isEmpty()) g.classpath.add(Path.of(e));
                    }
                }
                Files.deleteIfExists(cp);
                Path target = cp.getParent();
                try (var s = Files.list(target)) {
                    if (s.findAny().isEmpty()) Files.deleteIfExists(target);
                }
            } else {
                diag.report("warning", "JACKPOT_MAVEN_MODULE", "no classpath produced for module " + g.module + " (packaging pom?)");
            }
            PomModel model = PomModel.read(modulePom);
            Path sourceDir = moduleDir.resolve(model.sourceDirectory != null ? model.interpolate(model.sourceDirectory) : "src/main/java");
            if (Files.isDirectory(sourceDir)) {
                g.roots.add(sourceDir.normalize());
            }
            g.sourceLevel = model.sourceLevel();
            if (g.roots.isEmpty()) {
                continue; //aggregator / no sources
            }
            if (g.sourceLevel == null) {
                diag.report("warning", "JACKPOT_SOURCE_LEVEL_UNKNOWN", "module " + g.module + ": no maven.compiler.release/source found in the POM chain; pass --source explicitly");
            }
            groups.add(g);
        }
        if (groups.isEmpty()) {
            diag.report("error", "JACKPOT_MAVEN_NO_SOURCES", "no module with a source directory found under " + dir);
            return null;
        }
        return groups;
    }

    /** The module directory itself plus, recursively, the directories listed in {@code <modules>}. */
    private static List<Path> mavenModules(Path pom, Path dir, Diagnostics diag) throws IOException {
        List<Path> result = new ArrayList<>();
        Set<Path> seen = new LinkedHashSet<>();
        collectModules(pom, dir, result, seen);
        return result;
    }

    private static void collectModules(Path pom, Path dir, List<Path> result, Set<Path> seen) throws IOException {
        if (!seen.add(dir.toAbsolutePath().normalize())) return;
        result.add(dir);
        PomModel model = PomModel.read(pom);
        for (String module : model.modules) {
            Path md = dir.resolve(module).normalize();
            Path mp = Files.isDirectory(md) ? md.resolve("pom.xml") : md;
            if (Files.isRegularFile(mp)) {
                collectModules(mp, mp.getParent(), result, seen);
            }
        }
    }

    /** The parts of a POM (and its local parents) needed here. */
    static final class PomModel {
        final Map<String, String> properties = new LinkedHashMap<>();
        final List<String> modules = new ArrayList<>();
        String sourceDirectory;
        String compilerRelease, compilerSource, compilerTarget;

        static PomModel read(Path pom) throws IOException {
            PomModel m = new PomModel();
            m.fill(pom, 0);
            return m;
        }

        private void fill(Path pom, int depth) throws IOException {
            if (depth > 20 || !Files.isRegularFile(pom)) return;
            Document doc;
            try {
                DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
                f.setNamespaceAware(false);
                f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
                doc = f.newDocumentBuilder().parse(pom.toFile());
            } catch (Exception ex) {
                throw new IOException("cannot parse " + pom + ": " + ex.getMessage(), ex);
            }
            Element project = doc.getDocumentElement();
            //child values win over parent values: only set what is still unset
            for (Element p : children(child(project, "properties"))) {
                properties.putIfAbsent(p.getTagName(), text(p));
            }
            if (depth == 0) {
                for (Element mod : children(child(project, "modules"))) {
                    modules.add(text(mod));
                }
            }
            Element build = child(project, "build");
            if (build != null) {
                if (sourceDirectory == null) sourceDirectory = text(child(build, "sourceDirectory"));
                for (Element plugins : Arrays.asList(child(build, "plugins"), child(child(build, "pluginManagement"), "plugins"))) {
                    for (Element plugin : children(plugins)) {
                        if ("maven-compiler-plugin".equals(text(child(plugin, "artifactId")))) {
                            Element cfg = child(plugin, "configuration");
                            if (compilerRelease == null) compilerRelease = text(child(cfg, "release"));
                            if (compilerSource == null) compilerSource = text(child(cfg, "source"));
                            if (compilerTarget == null) compilerTarget = text(child(cfg, "target"));
                        }
                    }
                }
            }
            Element parent = child(project, "parent");
            if (parent != null) {
                String rel = text(child(parent, "relativePath"));
                Path parentPom = pom.getParent().resolve(rel != null ? rel : "../pom.xml").normalize();
                if (Files.isDirectory(parentPom)) parentPom = parentPom.resolve("pom.xml");
                fill(parentPom, depth + 1);
            }
        }

        String sourceLevel() {
            for (String v : Arrays.asList(compilerRelease, properties.get("maven.compiler.release"), compilerSource, properties.get("maven.compiler.source"), properties.get("java.version"))) {
                if (v != null && !v.isBlank()) {
                    String level = interpolate(v).trim();
                    if (level.matches("(1\\.)?\\d+")) return level;
                }
            }
            return null;
        }

        String interpolate(String v) {
            Matcher m = Pattern.compile("\\$\\{([^}]+)\\}").matcher(v);
            StringBuilder sb = new StringBuilder();
            int depth = 0;
            while (m.find() && depth++ < 10) {
                String val = properties.get(m.group(1));
                m.appendReplacement(sb, Matcher.quoteReplacement(val != null ? val : m.group(0)));
            }
            m.appendTail(sb);
            return sb.toString();
        }

        private static Element child(Element e, String name) {
            if (e == null) return null;
            for (Element c : children(e)) if (c.getTagName().equals(name)) return c;
            return null;
        }

        private static List<Element> children(Element e) {
            List<Element> l = new ArrayList<>();
            if (e == null) return l;
            NodeList nl = e.getChildNodes();
            for (int i = 0; i < nl.getLength(); i++) {
                Node n = nl.item(i);
                if (n instanceof Element) l.add((Element) n);
            }
            return l;
        }

        private static String text(Element e) {
            return e == null ? null : e.getTextContent().trim();
        }
    }

    // --- Gradle ---------------------------------------------------------------------

    private static final String GRADLE_INIT =
            "allprojects { p ->\n" +
            "  p.tasks.register('jackpotContext') {\n" +
            "    doLast {\n" +
            "      def ss = p.extensions.findByName('sourceSets')\n" +
            "      if (ss == null || ss.findByName('main') == null) return\n" +
            "      def main = ss.getByName('main')\n" +
            "      def level = null\n" +
            "      try { def jc = p.tasks.findByName('compileJava'); if (jc != null) { def r = jc.options.release.orNull; level = r != null ? r.toString() : jc.sourceCompatibility } } catch (Throwable t) {}\n" +
            "      println 'JACKPOT-MODULE ' + p.path\n" +
            "      main.java.srcDirs.each { println 'JACKPOT-ROOT ' + it.absolutePath }\n" +
            "      if (level != null) println 'JACKPOT-SOURCE ' + level\n" +
            "      main.compileClasspath.files.each { println 'JACKPOT-CP ' + it.absolutePath }\n" +
            "      println 'JACKPOT-END'\n" +
            "    }\n" +
            "  }\n" +
            "}\n";

    static List<Group> gradle(Path dir, Diagnostics diag) throws IOException, InterruptedException {
        boolean hasBuild = Files.exists(dir.resolve("build.gradle")) || Files.exists(dir.resolve("build.gradle.kts")) || Files.exists(dir.resolve("settings.gradle")) || Files.exists(dir.resolve("settings.gradle.kts"));
        if (!hasBuild) {
            diag.report("error", "JACKPOT_GRADLE_NO_BUILD", "no build.gradle[.kts] or settings.gradle[.kts] in " + dir);
            return null;
        }
        String gradle = findExecutable(dir, "gradlew", "gradle");
        if (gradle == null) {
            diag.report("error", "JACKPOT_GRADLE_NOT_FOUND", "neither ./gradlew nor gradle on PATH; Gradle must be installed to resolve the classpath");
            return null;
        }
        Path init = Files.createTempFile("jackpot-", ".init.gradle");
        try {
            Files.write(init, GRADLE_INIT.getBytes(StandardCharsets.UTF_8));
            Exec result = exec(Arrays.asList(gradle, "--offline", "-q", "--console=plain", "-I", init.toString(), "jackpotContext"), dir);
            if (result.exitCode != 0) {
                String out = result.output.replaceAll("\u001b\\[[0-9;]*m", "").trim();
                String code = out.contains("offline") || out.contains("Could not resolve") ? "JACKPOT_GRADLE_OFFLINE" : "JACKPOT_GRADLE_FAILED";
                diag.report("error", code, "gradle jackpotContext failed (exit " + result.exitCode + "); dependencies must be in the Gradle cache - run the project's build once, or pass the context yourself: --source <level> --classpath <jars> <source-root>. Output:\n" + tail(out, 15));
                return null;
            }
            List<Group> groups = new ArrayList<>();
            Group g = null;
            for (String line : result.output.split("\n")) {
                line = line.replaceAll("\u001b\\[[0-9;]*m", "").trim();
                if (line.startsWith("JACKPOT-MODULE ")) { String path = line.substring(15).trim(); g = new Group(":".equals(path) ? "." : path); }
                else if (g == null) continue;
                else if (line.startsWith("JACKPOT-ROOT ")) { Path r = Path.of(line.substring(13).trim()); if (Files.isDirectory(r)) g.roots.add(r.normalize()); }
                else if (line.startsWith("JACKPOT-SOURCE ")) g.sourceLevel = line.substring(15).trim();
                else if (line.startsWith("JACKPOT-CP ")) g.classpath.add(Path.of(line.substring(11).trim()));
                else if (line.equals("JACKPOT-END")) {
                    if (!g.roots.isEmpty()) {
                        if (g.sourceLevel == null) diag.report("warning", "JACKPOT_SOURCE_LEVEL_UNKNOWN", "project " + g.module + ": no release/sourceCompatibility found; pass --source explicitly");
                        groups.add(g);
                    }
                    g = null;
                }
            }
            if (groups.isEmpty()) {
                diag.report("error", "JACKPOT_GRADLE_NO_SOURCES", "no project with a main Java source set found under " + dir);
                return null;
            }
            return groups;
        } finally {
            Files.deleteIfExists(init);
        }
    }

    // --- process helpers ----------------------------------------------------------------

    static final class Exec {
        final int exitCode;
        final String output;
        Exec(int exitCode, String output) { this.exitCode = exitCode; this.output = output; }
    }

    private static Exec exec(List<String> cmd, Path dir) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        pb.environment().remove("MAVEN_OPTS");
        Process p = pb.start();
        byte[] out = p.getInputStream().readAllBytes();
        if (!p.waitFor(10, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            return new Exec(-1, "timed out after 10 minutes");
        }
        return new Exec(p.exitValue(), new String(out, StandardCharsets.UTF_8));
    }

    private static String findExecutable(Path dir, String wrapper, String name) {
        Path w = dir.resolve(wrapper);
        if (Files.isExecutable(w)) return w.toAbsolutePath().toString();
        String path = System.getenv("PATH");
        if (path != null) {
            for (String d : path.split(Pattern.quote(File.pathSeparator))) {
                Path c = Path.of(d).resolve(name);
                if (Files.isExecutable(c)) return c.toString();
            }
        }
        return null;
    }

    private static String tail(String s, int lines) {
        String[] all = s.split("\n");
        if (all.length <= lines) return s;
        return "...\n" + String.join("\n", Arrays.copyOfRange(all, all.length - lines, all.length));
    }
}
