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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.util.Enumeration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Anand Manissery
 */
class ClassPathResourceLoaderTest {

    private ClassPathResourceLoader classPathResourceLoader = new ClassPathResourceLoader();

    @Test
    void shouldReturnResourceUrls() throws IOException {
        Enumeration<URL> urls = classPathResourceLoader.getResourcesUrls("META-INF/MANIFEST.MF");
        assertThat(urls.hasMoreElements()).isTrue();
    }
}