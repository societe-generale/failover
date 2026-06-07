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

package com.societegenerale.failover.core.observable.manifest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author Anand Manissery
 */
class DefaultManifestInfoExtractorTest {

    private ManifestInfoExtractor manifestInfoExtractor;

    @BeforeEach
    void setup() {
        manifestInfoExtractor = new DefaultManifestInfoExtractor(new ClassPathResourceLoader());
    }

    @Test
    @DisplayName("should return the manifest info")
    void shouldReturnTheManifestInfo() {
        var result = manifestInfoExtractor.extract("slf4j-api");
        assertThat(result).containsEntry("lib-metadata-title", "slf4j-api").containsEntry("lib-metadata-version", "2.0.17");
    }

    @Test
    @DisplayName("should return empty map when no match found")
    void shouldReturnEmptyMapWhenNoMatchFound() {
        var result = manifestInfoExtractor.extract("some-jar-name");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should return empty map when fail to read manifest")
    void shouldReturnEmptyMapWhenFailToReadManifest() throws IOException {
        ResourceLoader resourceLoader = mock(ResourceLoader.class);
        when(resourceLoader.getResourcesUrls(anyString())).thenThrow(IOException.class);
        var manifestInfoExtractor = new DefaultManifestInfoExtractor(resourceLoader);
        var result = manifestInfoExtractor.extract("some-jar-name");
        assertThat(result).isEmpty();
    }
}
