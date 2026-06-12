/*
 * Copyright 2022-2026, Société Générale All rights reserved.
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

import java.util.Map;

/**
 * Extracts build metadata from {@code META-INF/MANIFEST.MF} for a given artifact title.
 *
 * @author Anand Manissery
 */
public interface ManifestInfoExtractor {

    /**
     * Returns build metadata (title, version, etc.) for the artifact with the given title.
     *
     * @param title the {@code Implementation-Title} value to look up
     * @return map of metadata key-value pairs; empty if no matching artifact is found
     */
    Map<String,String> extract(String title);
}