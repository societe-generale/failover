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

package com.societegenerale.failover.configuration;

import com.societegenerale.failover.aspect.FailoverAspect;

import com.societegenerale.failover.core.AdvancedFailoverHandler;
import com.societegenerale.failover.core.DefaultFailoverHandler;
import com.societegenerale.failover.core.FailoverHandler;
import com.societegenerale.failover.core.FailoverExecution;
import com.societegenerale.failover.core.BasicFailoverExecution;
import com.societegenerale.failover.core.clock.DefaultFailoverClock;
import com.societegenerale.failover.core.clock.FailoverClock;
import com.societegenerale.failover.core.expiry.*;
import com.societegenerale.failover.core.key.BeanFactoryKeyGeneratorLookup;
import com.societegenerale.failover.core.key.DefaultKeyGenerator;
import com.societegenerale.failover.core.key.KeyGenerator;
import com.societegenerale.failover.core.key.KeyGeneratorLookup;
import com.societegenerale.failover.core.key.FailoverKeyGenerator;
import com.societegenerale.failover.core.payload.DefaultPayloadEnricher;
import com.societegenerale.failover.core.payload.PassThroughRecoveredPayloadHandler;
import com.societegenerale.failover.core.payload.PayloadEnricher;
import com.societegenerale.failover.core.payload.RecoveredPayloadHandler;
import com.societegenerale.failover.core.report.LoggerReportPublisher;
import com.societegenerale.failover.core.report.MetricsReportPublisher;
import com.societegenerale.failover.core.report.ReportPublisher;
import com.societegenerale.failover.core.report.CompositeReportPublisher;
import com.societegenerale.failover.core.report.FailoverReporter;
import com.societegenerale.failover.core.report.DefaultFailoverReporter;
import com.societegenerale.failover.core.report.manifest.ManifestInfoExtractor;
import com.societegenerale.failover.core.report.manifest.CacheableManifestInfoExtractor;
import com.societegenerale.failover.core.report.manifest.ClassPathResourceLoader;
import com.societegenerale.failover.core.report.manifest.DefaultManifestInfoExtractor;
import com.societegenerale.failover.core.report.manifest.ResourceLoader;
import com.societegenerale.failover.core.scanner.DefaultFailoverScanner;
import com.societegenerale.failover.core.scanner.FailoverScanner;
import com.societegenerale.failover.core.store.FailoverStore;
import com.societegenerale.failover.properties.FailoverProperties;
import com.societegenerale.failover.properties.FailoverType;
import com.societegenerale.failover.properties.StoreType;
import com.societegenerale.failover.scheduler.ExpiryCleanupScheduler;
import com.societegenerale.failover.scheduler.ReportScheduler;
import com.societegenerale.failover.store.FailoverStoreAsync;
import com.societegenerale.failover.store.FailoverStoreInmemory;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.List;

/**
 * @author Anand Manissery
 */
@ConditionalOnExpression("${failover.enabled:true} eq true")
@EnableConfigurationProperties(FailoverProperties.class)
@Configuration
@AllArgsConstructor
@Slf4j
@EnableAspectJAutoProxy
@EnableAsync
@EnableScheduling
public class FailoverAutoConfiguration {

    private FailoverProperties failoverProperties;

    @ConditionalOnMissingBean
    @Bean
    public FailoverClock failoverClock() {
        return new DefaultFailoverClock();
    }

    @ConditionalOnMissingBean(name = "defaultKeyGenerator")
    @Bean(name = "defaultKeyGenerator")
    public KeyGenerator defaultKeyGenerator() {
        return new DefaultKeyGenerator();
    }

    @ConditionalOnMissingBean
    @Bean
    public KeyGeneratorLookup keyGeneratorLookup() {
        return new BeanFactoryKeyGeneratorLookup();
    }

    @ConditionalOnMissingBean(name = "failoverKeyGenerator")
    @Bean(name = "failoverKeyGenerator")
    public KeyGenerator failoverKeyGenerator(@Qualifier("defaultKeyGenerator") KeyGenerator defaultKeyGenerator, KeyGeneratorLookup keyGeneratorLookup) {
        return new FailoverKeyGenerator(defaultKeyGenerator, keyGeneratorLookup);
    }

    @ConditionalOnMissingBean
    @Bean
    public FailoverExpiryExtractor failoverExpiryExtractor() {
        return new BasicFailoverExpiryExtractor();
    }

    @ConditionalOnMissingBean(name = "defaultExpiryPolicy")
    @Bean
    public ExpiryPolicy<Object> defaultExpiryPolicy(FailoverClock clock, FailoverExpiryExtractor failoverExpiryExtractor) {
        return new DefaultExpiryPolicy<>(clock, failoverExpiryExtractor);
    }

    @ConditionalOnMissingBean
    @Bean
    public ExpiryPolicyLookup<Object> expiryPolicyLookup() {
        return new BeanFactoryExpiryPolicyLookup<>();
    }

    @ConditionalOnMissingBean(name = "failoverExpiryPolicy")
    @Bean
    public ExpiryPolicy<Object> failoverExpiryPolicy(@Qualifier("defaultExpiryPolicy") ExpiryPolicy<Object> defaultExpiryPolicy, ExpiryPolicyLookup<Object> expiryPolicyLookup) {
        return new FailoverExpiryPolicy<>(defaultExpiryPolicy, expiryPolicyLookup);
    }

    @ConditionalOnMissingBean
    @Bean
    public PayloadEnricher<Object> payloadEnricher() {
        return new DefaultPayloadEnricher<>();
    }

    @ConditionalOnMissingBean
    @Bean
    public FailoverScanner failoverScanner() {
        return new DefaultFailoverScanner(failoverProperties.getPackageToScan());
    }

    @ConditionalOnMissingBean
    @Bean
    public RecoveredPayloadHandler recoveredPayloadHandler() {
        return new PassThroughRecoveredPayloadHandler();
    }

    @Bean
    public ReportPublisher loggerReportPublisher(FailoverClock clock) {
        return new LoggerReportPublisher(clock);
    }

    @Bean
    public ReportPublisher metricsReportPublisher(FailoverClock clock) {
        return new MetricsReportPublisher(clock);
    }

    @ConditionalOnMissingBean
    @Bean
    public CompositeReportPublisher compositeReportPublisher(List<ReportPublisher> reportPublishers) {
        return new CompositeReportPublisher(reportPublishers);
    }

    @ConditionalOnProperty(prefix = "failover", name = "store.type", havingValue = "inmemory", matchIfMissing = true)
    @ConditionalOnMissingBean
    @Bean
    public FailoverStore<Object> failoverStoreInmemory() {
        log.warn("FailoverStore configured to FailoverStoreInmemory. We highly recommend to 'NOT to USE' FailoverStoreInmemory in PRODUCTION. Available options are : {{}}", (Object) StoreType.values());
        return new FailoverStoreAsync<>(new FailoverStoreInmemory<>());
    }

    @ConditionalOnMissingBean
    @Bean
    public FailoverHandler<Object> failoverHandler(@Qualifier("failoverKeyGenerator") KeyGenerator keyGenerator, @Qualifier("failoverExpiryPolicy")ExpiryPolicy<Object> expiryPolicy, FailoverClock clock, FailoverStore<Object> failoverStore, PayloadEnricher<Object> payloadEnricher, RecoveredPayloadHandler recoveredPayloadHandler, CompositeReportPublisher reportPublisher) {
        return new AdvancedFailoverHandler<>(new DefaultFailoverHandler<>(keyGenerator, clock, failoverStore, expiryPolicy, payloadEnricher), recoveredPayloadHandler, reportPublisher);
    }

    @ConditionalOnProperty(prefix = "failover", name = "type", havingValue = "basic", matchIfMissing = true)
    @ConditionalOnMissingBean
    @Bean
    public FailoverExecution<Object> failoverExecution(FailoverHandler<Object> failoverHandler) {
        log.info("FailoverExecution configured to BasicFailoverExecution. Available options are :  {{}}", (Object) FailoverType.values());
        return new BasicFailoverExecution<>(failoverHandler);
    }

    @ConditionalOnProperty(prefix = "failover", name = "aspect.enabled", havingValue = "true", matchIfMissing = true)
    @Bean
    public FailoverAspect<Object> failoverAspect(FailoverExecution<Object> failoverExecution) {
        return new FailoverAspect<>(failoverExecution);
    }

    @ConditionalOnMissingBean
    @Bean
    public ResourceLoader resourceLoader() {
        return new ClassPathResourceLoader();
    }

    @ConditionalOnMissingBean
    @Bean
    public ManifestInfoExtractor manifestInfoExtractor(ResourceLoader resourceLoader) {
        return new CacheableManifestInfoExtractor(new DefaultManifestInfoExtractor(resourceLoader));
    }

    @ConditionalOnMissingBean
    @Bean(initMethod = "report")
    public FailoverReporter failoverReporter(CompositeReportPublisher reportPublisher, FailoverScanner failoverScanner, FailoverClock clock, ManifestInfoExtractor manifestInfoExtractor) {
        return new DefaultFailoverReporter(reportPublisher, failoverScanner, clock, manifestInfoExtractor, failoverProperties.additionalInfo());
    }

    @ConditionalOnProperty(prefix = "failover", name = "scheduler.enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    @Bean
    public ReportScheduler reportScheduler(FailoverReporter failoverReporter) {
        return new ReportScheduler(failoverReporter);
    }

    @ConditionalOnProperty(prefix = "failover", name = "scheduler.enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    @Bean
    public ExpiryCleanupScheduler<Object> expiryCleanupScheduler(FailoverHandler<Object> failoverHandler) {
        return new ExpiryCleanupScheduler<>(failoverHandler);
    }
}
