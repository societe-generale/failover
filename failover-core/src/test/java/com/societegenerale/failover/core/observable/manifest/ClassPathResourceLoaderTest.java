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
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Anand Manissery
 */
class ClassPathResourceLoaderTest {

    private final ClassPathResourceLoader classPathResourceLoader = new ClassPathResourceLoader();

    @Test
    @DisplayName("should return resource urls")
    void shouldReturnResourceUrls() throws IOException {
        var urls = classPathResourceLoader.getResourcesUrls("META-INF/MANIFEST.MF");
        assertThat(urls.hasMoreElements()).isTrue();
    }
}