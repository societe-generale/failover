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
 * <p>Includes the split-package rules (audit A-1, fixed in Phase 4): store implementations live in
 * per-backend subpackages and the {@code BeanFactory*} lookup beans live in {@code ..lookup}, so no
 * two modules export the same package.
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

    /**
     * Split-package guard (audit A-1). The bare {@code com.societegenerale.failover.store} package is
     * owned by no module: every concrete store lives in a per-backend subpackage ({@code ..store.jdbc},
     * {@code ..store.caffeine}, {@code ..store.inmemory}, {@code ..store.async}, {@code ..store.multitenant})
     * and the core contract lives in {@code ..core.store}. Two JARs sharing one package break JPMS and shading.
     */
    @ArchTest
    static final ArchRule no_store_implementation_in_the_split_store_package =
            noClasses()
                    .that().areAssignableTo(FailoverStore.class)
                    .and().areNotInterfaces()
                    .should().resideInAPackage("com.societegenerale.failover.store")
                    .because("the bare ..store package was split across four JARs (A-1); stores belong in per-backend subpackages");

    /**
     * Split-package guard (audit A-1). The {@code BeanFactory*} lookup beans previously sat in
     * {@code failover-core}'s {@code key}/{@code expiry}/{@code payload.splitter} packages from a
     * separate {@code failover-lookup} JAR. They now live in {@code com.societegenerale.failover.lookup}.
     */
    @ArchTest
    static final ArchRule lookup_beans_reside_in_the_lookup_package =
            classes()
                    .that().haveSimpleNameStartingWith("BeanFactory")
                    .should().resideInAPackage("com.societegenerale.failover.lookup")
                    .because("BeanFactory*Lookup beans must not split failover-core's packages (A-1)");
}
