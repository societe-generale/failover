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

package com.societegenerale.failover.core.report.manifest;

import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * @author Anand Manissery
 */
@ExtendWith(MockitoExtension.class)
class CacheableManifestInfoExtractorTest {

    private static final String SOME_JAR = "some-jar";

    private static final Map<String, String> INFO = ImmutableMap.of("additional-info-key", "additional-info-value");

    @Mock
    private ManifestInfoExtractor manifestInfoExtractor;

    private CacheableManifestInfoExtractor cacheableManifestInfoExtractor;

    @BeforeEach
    void setUp() {
        cacheableManifestInfoExtractor = new CacheableManifestInfoExtractor(manifestInfoExtractor);
    }

    @Test
    void shouldReturnTheInfo() {
        given(manifestInfoExtractor.extract(SOME_JAR)).willReturn(INFO);
        Map<String, String> result = cacheableManifestInfoExtractor.extract(SOME_JAR);
        assertThat(result).containsExactlyEntriesOf(INFO);
        verify(manifestInfoExtractor).extract(SOME_JAR);
    }

    @Test
    void shouldReturnTheInfoFromCache() {
        given(manifestInfoExtractor.extract(SOME_JAR)).willReturn(INFO);

        // first call
        Map<String, String> result = cacheableManifestInfoExtractor.extract(SOME_JAR);
        assertThat(result).containsExactlyEntriesOf(INFO);

        // second call return from cache
        result = cacheableManifestInfoExtractor.extract(SOME_JAR);
        assertThat(result).containsExactlyEntriesOf(INFO);

        verify(manifestInfoExtractor, times(1)).extract(SOME_JAR);
    }
}