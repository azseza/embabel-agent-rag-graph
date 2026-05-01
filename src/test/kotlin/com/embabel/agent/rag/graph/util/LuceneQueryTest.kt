/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.agent.rag.graph.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class LuceneQueryTest {

    @Nested
    inner class BlankAndNullInputs {

        @Test
        fun `null returns wildcard`() {
            assertEquals("*", LuceneQuery.sanitize(null))
        }

        @Test
        fun `empty string returns wildcard`() {
            assertEquals("*", LuceneQuery.sanitize(""))
        }

        @Test
        fun `all-whitespace returns wildcard`() {
            assertEquals("*", LuceneQuery.sanitize("   "))
        }
    }

    @Nested
    inner class PlainText {

        @Test
        fun `plain text with no reserved chars is unchanged`() {
            assertEquals("hello world", LuceneQuery.sanitize("hello world"))
        }
    }

    @Nested
    inner class SingleReservedCharacters {

        @Test
        fun `plus sign is escaped`() {
            // "foo\+bar" — single backslash precedes the +
            assertEquals("foo\\+bar", LuceneQuery.sanitize("foo+bar"))
        }

        @Test
        fun `square brackets are escaped`() {
            // "\[trap\]"
            assertEquals("\\[trap\\]", LuceneQuery.sanitize("[trap]"))
        }

        @Test
        fun `backslash in input is escaped`() {
            // input:  path\to\file  (11 chars)
            // output: path\\to\\file (13 chars — each \ becomes \\)
            assertEquals("path\\\\to\\\\file", LuceneQuery.sanitize("path\\to\\file"))
        }
    }

    @Nested
    inner class TwoCharOperators {

        @Test
        fun `double ampersand operator is escaped`() {
            // "a \&& b"
            assertEquals("a \\&& b", LuceneQuery.sanitize("a && b"))
        }

        @Test
        fun `double pipe operator is escaped`() {
            // "a \|| b"
            assertEquals("a \\|| b", LuceneQuery.sanitize("a || b"))
        }
    }
}
