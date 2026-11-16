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

import com.google.gerrit.openapi.parse.SourceIndex.ClassInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Regression for supertype / nested-type resolution. Run as a main-based java_test
 * (use_testrunner = False): any mismatch throws and fails the test.
 *
 * <p>Covers the two inheritance-field-loss bugs: a nested member type whose simple
 * name also names a same-package top-level class, and a superclass referenced by a
 * fully-qualified {@code extends}.
 */
public final class ResolutionTest {
  public static void main(String[] args) throws Exception {
    Path dir = Files.createTempDirectory("resolution-test");
    // p.Outer.Dup (nested) collides by simple name with the top-level p.Dup.
    write(
        dir,
        "p/Outer.java",
        "package p; public class Outer {"
            + " public static class Dup extends Base { public int inner; } }");
    write(dir, "p/Dup.java", "package p; public class Dup { public int top; }");
    write(dir, "p/Base.java", "package p; public class Base { public int base; }");
    // q.Sub extends its base by fully-qualified name, not a simple-name import.
    write(dir, "q/Sub.java", "package q; public class Sub extends p.Base { public int sub; }");

    SourceIndex idx = new SourceIndex();
    idx.parse(
        List.of(
            dir.resolve("p/Outer.java"),
            dir.resolve("p/Dup.java"),
            dir.resolve("p/Base.java"),
            dir.resolve("q/Sub.java")),
        System.err);

    // A nested member type shadows a same-package top-level class of the same name
    // (JLS 6.5.5.1). Inside p.Outer, "Dup" must be p.Outer.Dup, not p.Dup.
    ClassInfo outer = idx.byFqcn().get("p.Outer");
    ClassInfo dup = idx.resolveClass("Dup", outer);
    check(
        "nested member shadows same-package top-level",
        dup != null && "p.Outer.Dup".equals(dup.fqcn),
        dup == null ? "null" : dup.fqcn);

    // A fully-qualified `extends` resolves to the real base, not the subclass itself.
    ClassInfo sub = idx.byFqcn().get("q.Sub");
    List<ClassInfo> chain = AstUtil.superclassChain(sub, idx::resolveClass);
    boolean hasBase = chain.stream().anyMatch(c -> "p.Base".equals(c.fqcn));
    check(
        "fully-qualified extends resolves the base",
        hasBase,
        chain.stream().map(c -> c.fqcn).toList().toString());

    System.err.println("ResolutionTest: OK");
  }

  private static void write(Path dir, String rel, String src) throws Exception {
    Path f = dir.resolve(rel);
    Files.createDirectories(f.getParent());
    Files.writeString(f, src);
  }

  private static void check(String what, boolean ok, String actual) {
    if (!ok) {
      throw new AssertionError(what + " FAILED: got " + actual);
    }
  }

  private ResolutionTest() {}
}
