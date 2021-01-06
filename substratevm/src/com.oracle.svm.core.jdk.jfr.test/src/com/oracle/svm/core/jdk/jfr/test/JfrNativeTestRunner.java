/*
 * Copyright (c) 2021, Red Hat Inc. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.oracle.svm.core.jdk.jfr.test;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.StringJoiner;

public class JfrNativeTestRunner {
    public static void main(String[] main) throws IOException, ClassNotFoundException {
        JfrNativeTestRunner runner = new JfrNativeTestRunner();
        runner.runTests();
    }

    private void runTests() throws IOException, ClassNotFoundException {
        ClassScanner scanner = new ClassScanner();
        scanner.scanClasses(this::scanClass);
    }

    @SuppressWarnings(value = "unchecked")
    private Class<? extends JfrNativeTestCase> narrowClass(Class<?> cls) {
        return (Class<? extends JfrNativeTestCase>) cls;
    }

    private static String getRunnerClassName(Class<? extends JfrNativeTestCase> cls, String suffix) {
        return cls.getSimpleName() + "Runner" + suffix;
    }

    @SuppressWarnings(value = "unchecked")
    private static Class<? extends JfrNativeTestCase> narrowTestClass(Class<?> clazz) {
        return (Class<? extends JfrNativeTestCase>) clazz;
    }

    private static void deleteFile(Path path) throws IOException {
        if (path != null) {
            Files.deleteIfExists(path);
        }
    }

    private void scanClass(Class<?> cls) throws IOException {
        if (JfrNativeTestCase.class.isAssignableFrom(cls) && (cls.getModifiers() & (Modifier.ABSTRACT | Modifier.INTERFACE)) == 0) {
            Class<? extends JfrNativeTestCase> testClazz = narrowTestClass(cls);
            Path runnerSourceFile = null;
            Path image = null;
            Path recording = null;
            try {
                recording = Files.createTempFile("test-recording", ".jfr");
                System.out.println("Recording in: " + recording);
                runnerSourceFile = generateRunner(testClazz, recording);
                compileRunner(runnerSourceFile);
                image = buildNativeImage(testClazz);
                executeNativeImage(image);
                String xml = parseRecordingToXML(recording);
                invokeVerification(narrowClass(testClazz), xml);
            } finally {
                deleteFile(image);
                if (image != null) {
                    deleteFile(Paths.get(image.toString(), ".o")); // Possible left-overs from native-compilation
                }
                deleteFile(Paths.get(System.getProperty("java.io.tmpdir"), getRunnerClassName(testClazz, ".class")));
                deleteFile(runnerSourceFile);
                deleteFile(recording);
            }
        }
    }

    private Path generateRunner(Class<? extends JfrNativeTestCase> cls, Path recording) throws IOException {
        String testClassName = cls.getName();
        System.out.println("Generating native runner for: " + testClassName);
        String tmpDir = System.getProperty("java.io.tmpdir");
        String runnerClassName = getRunnerClassName(cls, "");
        Path runnerSourceFile = Files.createFile(Paths.get(tmpDir, getRunnerClassName(cls, ".java")));
        System.out.println("Java source file: " + runnerSourceFile);
        String path = recording.toUri().toString();
        BufferedWriter writer = new BufferedWriter(new FileWriter(runnerSourceFile.toFile()));
        writer.write("import java.net.URI;\n");
        writer.write("import java.nio.file.Path;\n");
        writer.write("import jdk.jfr.Configuration;\n");
        writer.write("import jdk.jfr.Recording;\n");
        writer.write("public class " + runnerClassName + "{\n");
        writer.write("    public static void main(String[] args) throws Exception {\n");
        writer.write("        " + testClassName + " test = new " + testClassName + "();\n");
        writer.write("        Configuration c = Configuration.getConfiguration(\"default\");\n");
        writer.write("        Recording r = new Recording(c);\n");
        writer.write("        r.start();\n");
        writer.write("        test.test();\n");
        writer.write("        r.stop();\n");
        writer.write("        Path tmpfile = Path.of(new URI(\"" + path + "\"));\n");
        writer.write("        r.dump(tmpfile);\n");
        writer.write("        r.close();\n");
        writer.write("    }\n");
        writer.write("}\n");
        writer.close();
        return runnerSourceFile;
    }

    private void compileRunner(Path sourceFile) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        compiler.run(null, null, null, "-d", System.getProperty("java.io.tmpdir"), sourceFile.toString());
    }

    private Path buildNativeImage(Class<? extends JfrNativeTestCase> cls) throws IOException {
        Path image = Files.createTempFile("ïmage", "");
        System.out.println("create image: " + image.toString());
        ProcessBuilder pb = new ProcessBuilder("mx", "native-image", "-H:+ReportExceptionStackTraces",  "-H:+FlightRecorder", "--no-fallback", "-ea",
                                        "-cp", System.getProperty("java.io.tmpdir") + System.getProperty("path.separator") + System.getProperty("java.class.path"),
                                        getRunnerClassName(cls, ""), image.toString());
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
        Process proc = pb.start();
        try {
            proc.waitFor();
        } catch (InterruptedException ex) {
            ex.printStackTrace();
            throw new RuntimeException(ex);
        }
        return image;
    }

    private void executeNativeImage(Path image) throws IOException {
        System.err.println("executing native image: " + image);
        ProcessBuilder pb = new ProcessBuilder(image.toString());
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
        Process proc = pb.start();
        try {
            proc.waitFor();
        } catch (InterruptedException ex) {
            ex.printStackTrace();
            throw new RuntimeException(ex);
        }
    }

    private String parseRecordingToXML(Path recording) throws IOException {
        System.out.println("Parsing recording and generate XML: " + recording);
        ProcessBuilder pb = new ProcessBuilder("jfr", "print", "--xml", recording.toString());
        Process proc = pb.start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
        StringJoiner sj = new StringJoiner(System.getProperty("line.separator"));
        reader.lines().iterator().forEachRemaining(sj::add);
        try {
            proc.waitFor();
        } catch (InterruptedException ex) {
            ex.printStackTrace();
            throw new RuntimeException(ex);
        }
        return sj.toString();
    }

    private void invokeVerification(Class<? extends JfrNativeTestCase> cls, String xml) {
        System.out.println("Invoking verification");
        try {
            JfrNativeTestCase test = cls.getDeclaredConstructor().newInstance();
            test.verify(xml);
        } catch (NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException ex) {
            // Can't really happen.
            throw new InternalError(ex);
        }
    }
}
