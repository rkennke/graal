/*
 * Copyright (c) 2016, 2017, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.util;

import java.util.Collections;

import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

import com.oracle.svm.core.option.SubstrateOptionsParser;

import jdk.graal.compiler.options.OptionKey;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;

/**
 * SVM mechanism for handling user errors and warnings that should be reported to the command line.
 */
@Platforms(Platform.HOSTED_ONLY.class)
@SuppressWarnings("serial")
public class UserError {

    /**
     * Stop compilation immediately and report the message to the user.
     *
     * @param format format string (must not start with a lowercase letter)
     * @param args arguments for the format string that are {@link #formatArguments(Object...)
     *            preprocessed} before being sent to {@link String#format(String, Object...)}
     */
    public static UserException abort(String format, Object... args) {
        throw new UserException(String.format(format, formatArguments(args)));
    }

    /**
     * UserException type for all errors that should be reported to the SVM users.
     */
    @Platforms(Platform.HOSTED_ONLY.class)
    public static class UserException extends Error {
        static final long serialVersionUID = 75431290632980L;
        private final Iterable<String> messages;

        public UserException(String msg) {
            this(Collections.singletonList(msg));
        }

        protected UserException(Iterable<String> messages) {
            super(String.join(System.lineSeparator(), messages));
            this.messages = messages;
        }

        public UserException(String msg, Throwable throwable) {
            this(Collections.singletonList(msg), throwable);
        }

        protected UserException(Iterable<String> messages, Throwable throwable) {
            super(String.join(System.lineSeparator(), messages), throwable);
            this.messages = messages;
        }

        public Iterable<String> getMessages() {
            return messages;
        }
    }

    /**
     * Stop compilation immediately and report the message to the user.
     *
     * @param cause the exception that caused the abort.
     * @param format format string (must not start with a lowercase letter)
     * @param args arguments for the format string that are {@link #formatArguments(Object...)
     *            preprocessed} before being sent to {@link String#format(String, Object...)}
     */
    public static UserException abort(Throwable cause, String format, Object... args) {
        throw ((UserException) new UserException(String.format(format, formatArguments(args))).initCause(cause));
    }

    /**
     * Concisely reports user errors.
     *
     * @param format format string (must not start with a lowercase letter)
     * @param args arguments for the format string that are {@link #formatArguments(Object...)
     *            preprocessed} before being sent to {@link String#format(String, Object...)}
     */
    public static void guarantee(boolean condition, String format, Object... args) {
        if (!condition) {
            throw UserError.abort(format, args);
        }
    }

    /**
     * Processes {@code args} to convert selected values to strings.
     * <ul>
     * <li>A {@link ResolvedJavaType} is converted with {@link ResolvedJavaType#toJavaName}
     * {@code (true)}.</li>
     * <li>A {@link ResolvedJavaMethod} is converted with {@link ResolvedJavaMethod#format}
     * {@code ("%H.%n($p)")}.</li>
     * <li>A {@link ResolvedJavaField} is converted with {@link ResolvedJavaField#format}
     * {@code ("%H.%n")}.</li>
     * </ul>
     * All other values are copied to the returned array unmodified.
     *
     * @param args arguments to process
     * @return a copy of {@code args} with certain values converted to strings as described above
     */
    static Object[] formatArguments(Object... args) {
        return VMError.formatArguments(args);
    }

    /**
     * Stop compilation immediately and report the message to the user.
     *
     * @param messages the error message to be reported to the user.
     */
    public static UserException abort(Iterable<String> messages) {
        throw new UserException(messages);
    }

    /**
     * Stop compilation immediately and report the invalid use of an option to the user.
     *
     * @param option the option incorrectly used.
     * @param value the value passed to the option, possibly invalid.
     * @param reason the reason why the option-value pair is rejected that can be understood by the
     *            user.
     */
    public static UserException invalidOptionValue(OptionKey<?> option, String value, String reason) {
        return abort("Invalid option '%s'. %s.", SubstrateOptionsParser.commandArgument(option, value), reason);
    }

    /**
     * @see #invalidOptionValue(OptionKey, String, String)
     */
    public static UserException invalidOptionValue(OptionKey<?> option, Boolean value, String reason) {
        return invalidOptionValue(option, value ? "+" : "-", reason);
    }

    /**
     * @see #invalidOptionValue(OptionKey, String, String)
     */
    public static UserException invalidOptionValue(OptionKey<?> option, Number value, String reason) {
        return invalidOptionValue(option, String.valueOf(value), reason);
    }
}
