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

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URL;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.Manifest;

/**
 * @author Anand Manissery
 */
@Slf4j
public class DefaultManifestInfoExtractor implements ManifestInfoExtractor {

    private final ResourceLoader resourceLoader;

    public DefaultManifestInfoExtractor(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    public Map<String,String> extract(String title) {
        Map<String,String> info = new LinkedHashMap<>();
        try {
            Enumeration<URL> resources = resourceLoader.getResourcesUrls("META-INF/MANIFEST.MF");
            while (resources.hasMoreElements()) {
                Manifest manifest = new Manifest(resources.nextElement().openStream());
                String manifestTitle = manifest.getMainAttributes().getValue("Implementation-Title");
                if (title.equals(manifestTitle)) {
                    info.put("lib-metadata-title", manifestTitle);
                    info.put("lib-metadata-version", manifest.getMainAttributes().getValue("Implementation-Version"));
                    break;
                }
            }
        } catch (IOException exception) {
            log.error("Exception while extracting manifest information.", exception);
        }
        return info;
    }
}