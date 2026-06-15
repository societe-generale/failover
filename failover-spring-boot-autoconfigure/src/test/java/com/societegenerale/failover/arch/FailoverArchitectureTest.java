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

package com.societegenerale.failover.arch;

import com.societegenerale.failover.core.store.FailoverStore;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * ArchUnit architecture tests (audit T-3). Enforces the structural invariants that the decorator
 * design relies on but that no compiler checks. Runs in the autoconfigure module because it is the
 * only module with every {@code failover-*} artifact on its classpath, so a single import covers the
 * whole library.
 *
 * <p>The split-package rule (audit A-1) is intentionally <b>not</b> enforced here — it is a Phase 4
 * breaking change. These rules cover invariants that hold today.
 *
 * @author Anand Manissery
 */
@AnalyzeClasses(packages = "com.societegenerale.failover", importOptions = ImportOption.DoNotIncludeTests.class)
class FailoverArchitectureTest {

    /**
     * The async decorator must never read {@code ThreadLocal} state: tenant/security context is
     * resolved on the calling thread by the outermost {@code MultiTenantFailoverStore} before the
     * executor boundary (ADR 19, ADR 20). Reading a ThreadLocal inside an async lambda would bind to
     * the wrong context on a pooled thread.
     */
    @ArchTest
    static final ArchRule async_store_must_not_depend_on_threadlocal =
            noClasses()
                    .that().haveSimpleName("FailoverStoreAsync")
                    .should().dependOnClassesThat().areAssignableTo(ThreadLocal.class)
                    .because("async write lambdas must never read ThreadLocal; context is bound on the calling thread (ADR 19/20)");

    /**
     * Every concrete {@link FailoverStore} carries {@code FailoverStore} in its name — either as a
     * prefix with the backing type as a suffix ({@code FailoverStoreJdbc}, {@code FailoverStoreAsync})
     * or as a suffix on a decorator ({@code DefaultFailoverStore}, {@code MultiTenantFailoverStore}).
     * Keeps the persistence layer discoverable and consistent across modules.
     */
    @ArchTest
    static final ArchRule failover_store_implementations_are_named_consistently =
            classes()
                    .that().areAssignableTo(FailoverStore.class)
                    .and().areNotInterfaces()
                    .should().haveSimpleNameContaining("FailoverStore")
                    .because("FailoverStore implementations carry 'FailoverStore' in their name");

    /**
     * No cyclic dependencies between the top-level functional slices of the library. Catches
     * accidental back-references that would erode the layered decorator architecture.
     */
    @ArchTest
    static final ArchRule slices_are_free_of_cycles =
            slices()
                    .matching("com.societegenerale.failover.(*)..")
                    .should().beFreeOfCycles();
}
