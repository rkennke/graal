/*
 * Copyright (c) 2012, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.api.dsl;

import com.oracle.truffle.api.nodes.Node;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * <p>
 * Each {@link Node} has one {@link TypeSystem} at its root to define the types that can be used
 * throughout the system. Multiple {@link TypeSystem}s are allowed, but they cannot be mixed inside
 * a single {@link Node} hierarchy. A {@link TypeSystem} optionally defines a list of types as its
 * child elements, in which every type precedes its super types. The latter condition ensures that
 * the most concrete type is found first when searching the list sequentially for the type of a
 * given generic value.
 * </p>
 *
 * <p>
 * Each {@link #value()} is represented as a Java type. A type can specify two annotations:
 * {@link TypeCheck} and {@link TypeCast}. The {@link TypeCheck} checks whether a given generic
 * value matches to the current type. The {@link TypeCast} casts a generic type value to the current
 * type. If the {@link TypeCheck} and {@link TypeCast} annotations are not declared in the
 * {@link TypeSystem} the a default implementation is provided. The default implementation of
 * {@link TypeCheck} returns <code>true</code> only on an exact type match and {@link TypeCast} is
 * only a cast to this type. Specified methods with {@link TypeCheck} and {@link TypeCast} may be
 * used to extend the definition of a type in the language. In our example, the
 * <code>isInteger</code> and <code>asInteger</code> methods are defined in a way so that they
 * accept also {@link Integer} values, implicitly converting them to {@link Double} . This example
 * points out how we express implicit type conversions.
 * </p>
 *
 * <p>
 * <b>Example:</b> The {@link TypeSystem} contains the types {@link Boolean}, {@link Integer}, and
 * {@link Double}. The type {@link Object} is always used implicitly as the generic type represent
 * all values.
 *
 * <pre>
 *
 * {@literal @}TypeSystem(types = {boolean.class, int.class, double.class})
 * public abstract class ExampleTypeSystem {
 * 
 *     {@literal @}TypeCheck
 *     public boolean isInteger(Object value) {
 *         return value instanceof Integer || value instanceof Double;
 *     }
 * 
 *     {@literal @}TypeCast
 *     public double asInteger(Object value) {
 *         return ((Number)value).doubleValue();
 *     }
 * }
 * </pre>
 *
 *
 * @see TypeCast
 * @see TypeCheck
 * @since 0.8 or earlier
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE})
public @interface TypeSystem {

    /**
     * The list of types as child elements of the {@link TypeSystem}. Each precedes its super type.
     * 
     * @since 0.8 or earlier
     */
    Class<?>[] value() default {};

}