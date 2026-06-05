/*
 * Copyright 2022-2023, Société Générale All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.societegenerale.failover.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Anand Manissery
 */
class MetadataTest {

    @Test
    @DisplayName("info map is empty on new instance")
    void infoMapIsEmptyOnNewInstance() {
        assertThat(new Metadata().getInfo()).isEmpty();
    }

    @Test
    @DisplayName("withInfo stores the key-value pair")
    void withInfoStoresKeyValuePair() {
        var metadata = new Metadata().withInfo("exception-name", "java.io.IOException");

        assertThat(metadata.getInfo())
                .hasSize(1)
                .containsEntry("exception-name", "java.io.IOException");
    }

    @Test
    @DisplayName("withInfo returns the same Metadata instance for chaining")
    void withInfoReturnsSameInstance() {
        var metadata = new Metadata();

        var result = metadata.withInfo("k", "v");

        assertThat(result).isSameAs(metadata);
    }

    @Test
    @DisplayName("withInfo supports chaining multiple entries")
    void withInfoChainAddsAllEntries() {
        var metadata = new Metadata()
                .withInfo("exception-name", "java.lang.RuntimeException")
                .withInfo("cause", "upstream timeout");

        assertThat(metadata.getInfo())
                .containsEntry("exception-name", "java.lang.RuntimeException")
                .containsEntry("cause", "upstream timeout")
                .hasSize(2);
    }

    @Test
    @DisplayName("withInfo preserves insertion order")
    void withInfoPreservesInsertionOrder() {
        var metadata = new Metadata()
                .withInfo("first", "1")
                .withInfo("second", "2")
                .withInfo("third", "3");

        assertThat(metadata.getInfo().keySet())
                .containsExactly("first", "second", "third");
    }

    @Test
    @DisplayName("withInfo overwrites existing key")
    void withInfoOverwritesExistingKey() {
        var metadata = new Metadata()
                .withInfo("cause", "original")
                .withInfo("cause", "updated");

        assertThat(metadata.getInfo())
                .containsEntry("cause", "updated")
                .hasSize(1);
    }

    @Test
    @DisplayName("two Metadata instances with same entries are equal")
    void twoMetadataWithSameEntriesAreEqual() {
        var m1 = new Metadata().withInfo("k", "v");
        var m2 = new Metadata().withInfo("k", "v");

        assertThat(m1).isEqualTo(m2);
    }

    @Test
    @DisplayName("two metadata instances with same entries with different are equal")
    void twoMetadataWithSameEntriesWithDifferentOrderAreEqual() {
        var m1 = new Metadata().withInfo("k1", "v1").withInfo("k2", "v2" );
        var m2 = new Metadata().withInfo("k2", "v2").withInfo("k1", "v1");
        assertThat(m1).isEqualTo(m2);
    }

    @Test
    @DisplayName("two Metadata instances with different entries are not equal")
    void twoMetadataWithDifferentEntriesAreNotEqual() {
        var m1 = new Metadata().withInfo("k", "v1");
        var m2 = new Metadata().withInfo("k", "v2");

        assertThat(m1).isNotEqualTo(m2);
    }
}
