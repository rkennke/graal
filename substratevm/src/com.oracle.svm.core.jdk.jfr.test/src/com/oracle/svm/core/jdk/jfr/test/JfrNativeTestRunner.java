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
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private void scanClass(Class<?> cls) throws IOException {
        if (JfrNativeTestCase.class.isAssignableFrom(cls) && (cls.getModifiers() & (Modifier.ABSTRACT | Modifier.INTERFACE)) == 0) {
            Path recording = Files.createTempFile("test-recording", ".jfr");
            System.out.println("Recording in: " + recording);
            File runnerSourceFile = generateRunner(cls, recording);
            compileRunner(runnerSourceFile);
            Path image = buildNativeImage(cls);
            executeNativeImage(image);
            String xml = parseRecordingToXML(recording);
            invokeVerification(narrowClass(cls), xml);
        }
    }

    private File generateRunner(Class<?> cls, Path recording) throws IOException {
        String testClassName = cls.getSimpleName();
        System.out.println("Generating native runner for: " + testClassName);
        String tmpDir = System.getProperty("java.io.tmpdir");
        String runnerClassName = testClassName + "Runner";
        File runnerSourceFile = new File(tmpDir, runnerClassName + ".java");
        System.out.println("Java source file: " + runnerSourceFile);
        String path = recording.toUri().toString();
        BufferedWriter writer = new BufferedWriter(new FileWriter(runnerSourceFile));
        writer.write("package " + cls.getPackage().getName() + ";\n");
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

    private void compileRunner(File sourceFile) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        compiler.run(null, null, null, "-d", System.getProperty("java.io.tmpdir"), sourceFile.getPath());
    }

    private Path buildNativeImage(Class<?> cls) throws IOException {
        Path image = Files.createTempFile("ïmage", "");
        System.out.println("create image: " + image.toString());
        ProcessBuilder pb = new ProcessBuilder("mx", "native-image", "-H:+ReportExceptionStackTraces",  "-H:+FlightRecorder", "--no-fallback", "-ea",
                                        "-cp", System.getProperty("java.io.tmpdir") + System.getProperty("path.separator") + System.getProperty("java.class.path"),
                                        cls.getName() + "Runner", image.toString());
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
