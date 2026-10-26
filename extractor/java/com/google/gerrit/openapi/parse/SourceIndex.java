// Copyright (C) 2026 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.openapi.parse;

import com.google.gerrit.common.Nullable;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Parses a source manifest with javac's Compiler Tree API into a queryable table of classes, and
 * resolves simple/nested type names against it. This is the shared, hermetic parse foundation the
 * OpenAPI tools sit on; keeping it here (rather than in {@code RestEndpoints}) is why {@code
 * KindIndex}, {@code PathRootResolver}, and {@code ResponseInferencer} can depend on {@link
 * ClassInfo} without reaching into the extractor.
 *
 * <p>Parse-only: no attribution, no classpath. Parse errors are fatal (a file that fails to parse
 * could hide bindings, so the audit refuses to emit a partial result).
 */
public final class SourceIndex {

  /**
   * A parsed class: the pure output of parsing, with no analysis state attached. Public with public
   * fields because the analysis packages ({@code analyze}, {@code path}, {@code bind}) read it
   * directly; promoting to an accessor-based model is a planned follow-up.
   */
  public static final class ClassInfo {
    public String fqcn; // dotted, e.g. a.b.Outer.Inner
    public String binaryName; // JVM binary name, e.g. a.b.Outer$Inner
    public String packageName;
    public Map<String, String> imports;
    public ClassTree tree;
  }

  private final Map<String, ClassInfo> byFqcn = new LinkedHashMap<>();

  public Map<String, ClassInfo> byFqcn() {
    return byFqcn;
  }

  public void parse(List<Path> files, PrintStream log) throws IOException {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      throw new IllegalStateException("no system Java compiler (need a JDK)");
    }
    List<java.io.File> jfiles = new ArrayList<>();
    for (Path p : files) {
      jfiles.add(p.toFile());
    }
    DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
    try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {
      JavacTask task =
          (JavacTask)
              compiler.getTask(
                  null, fm, diags, List.of(), null, fm.getJavaFileObjectsFromFiles(jfiles));
      int n = 0;
      for (CompilationUnitTree unit : task.parse()) {
        collectClasses(unit);
        n++;
      }
      log.printf("parsed %d source files, %d classes%n", n, byFqcn.size());
      List<String> errors =
          diags.getDiagnostics().stream()
              .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
              .map(d -> d.getSource() + ": " + d.getMessage(null))
              .toList();
      if (!errors.isEmpty()) {
        errors.stream().limit(5).forEach(e -> log.println("  parse error: " + e));
        throw new IllegalStateException(errors.size() + " parse error(s)");
      }
    }
  }

  private void collectClasses(CompilationUnitTree unit) {
    String pkg = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
    Map<String, String> imports = new LinkedHashMap<>();
    for (ImportTree imp : unit.getImports()) {
      if (imp.isStatic()) {
        continue;
      }
      String fq = imp.getQualifiedIdentifier().toString();
      int dot = fq.lastIndexOf('.');
      if (dot > 0 && !fq.endsWith("*")) {
        imports.put(fq.substring(dot + 1), fq);
      }
    }
    for (Tree decl : unit.getTypeDecls()) {
      if (decl instanceof ClassTree ct) {
        recordClass(ct, pkg, "", imports);
      }
    }
  }

  private void recordClass(
      ClassTree ct, String pkg, String outerChain, Map<String, String> imports) {
    String simple = ct.getSimpleName().toString();
    if (simple.isEmpty()) {
      return;
    }
    String dotted = outerChain.isEmpty() ? simple : outerChain + "." + simple;
    String binaryOuter = outerChain.replace('.', '$');
    String binaryTail = binaryOuter.isEmpty() ? simple : binaryOuter + "$" + simple;
    ClassInfo ci = new ClassInfo();
    ci.packageName = pkg;
    ci.fqcn = pkg.isEmpty() ? dotted : pkg + "." + dotted;
    ci.binaryName = pkg.isEmpty() ? binaryTail : pkg + "." + binaryTail;
    ci.imports = imports;
    ci.tree = ct;
    byFqcn.put(ci.fqcn, ci);
    for (Tree member : ct.getMembers()) {
      if (member instanceof ClassTree nested) {
        recordClass(nested, pkg, dotted, imports);
      }
    }
  }

  /** Resolve a simple, {@code Outer.Inner}, or fully-qualified name against the parsed classes. */
  @Nullable
  public ClassInfo resolveClass(@Nullable String name, ClassInfo ctx) {
    if (name == null) {
      return null;
    }
    if (byFqcn.containsKey(name)) {
      return byFqcn.get(name); // already a known fqcn
    }
    // Nested (Outer.Inner) or fully-qualified: resolve the head, then append the nested suffix.
    if (name.contains(".")) {
      int dot = name.indexOf('.');
      ClassInfo outer = resolveClass(name.substring(0, dot), ctx);
      String nested =
          (outer != null ? outer.fqcn : name.substring(0, dot)) + "." + name.substring(dot + 1);
      return byFqcn.get(nested);
    }
    String imp = ctx.imports.get(name);
    if (imp != null && byFqcn.containsKey(imp)) {
      return byFqcn.get(imp);
    }
    String samePkg = ctx.packageName.isEmpty() ? name : ctx.packageName + "." + name;
    if (byFqcn.containsKey(samePkg)) {
      return byFqcn.get(samePkg);
    }
    // A nested class of ctx or an enclosing class (e.g. "Email" inside AccountResource).
    String outer = ctx.fqcn;
    while (outer != null && outer.length() > ctx.packageName.length()) {
      if (byFqcn.containsKey(outer + "." + name)) {
        return byFqcn.get(outer + "." + name);
      }
      int lastDot = outer.lastIndexOf('.');
      outer = lastDot > ctx.packageName.length() ? outer.substring(0, lastDot) : null;
    }
    return byFqcn.get(name);
  }
}
