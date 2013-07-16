package com.yammer.tenacity.tests;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.netflix.hystrix.*;
import com.netflix.hystrix.strategy.properties.HystrixPropertiesFactory;
import com.netflix.hystrix.util.HystrixRollingNumberEvent;
import com.yammer.dropwizard.config.Configuration;
import com.yammer.tenacity.core.TenacityCommand;
import com.yammer.tenacity.core.TenacityPropertyStore;
import com.yammer.tenacity.core.TenacityPropertyStoreBuilder;
import com.yammer.tenacity.core.config.CircuitBreakerConfiguration;
import com.yammer.tenacity.core.config.TenacityConfiguration;
import com.yammer.tenacity.core.config.ThreadPoolConfiguration;
import com.yammer.tenacity.core.properties.TenacityCommandProperties;
import com.yammer.tenacity.core.properties.TenacityPropertyKey;
import com.yammer.tenacity.core.properties.TenacityThreadPoolProperties;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.Future;

import static org.fest.assertions.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class TenacityPropertiesTest extends TenacityTest {
    private TenacityPropertyStore tenacityPropertyStore;

    @Before
    public void setup() {
        tenacityPropertyStore = new TenacityPropertyStore();
    }

    @Test
    public void executeCorrectly() throws Exception {
        assertThat(new TenacitySuccessCommand(tenacityPropertyStore).execute()).isEqualTo("value");
        assertThat(new TenacitySuccessCommand(tenacityPropertyStore).queue().get()).isEqualTo("value");
    }

    @Test
    public void fallbackCorrectly() throws Exception {
        assertThat(new TenacityFailingCommand(tenacityPropertyStore).execute()).isEqualTo("fallback");
        assertThat(new TenacityFailingCommand(tenacityPropertyStore).queue().get()).isEqualTo("fallback");
    }

    private static class OverridePropertiesBuilder extends TenacityPropertyStoreBuilder<OverrideConfiguration> {
        private final DependencyKey key;
        private OverridePropertiesBuilder(DependencyKey key,
                                          OverrideConfiguration configuration) {
            super(configuration);
            this.key = key;
        }

        @Override
        public ImmutableMap<TenacityPropertyKey, HystrixCommandProperties.Setter> buildCommandProperties() {

            return ImmutableMap.<TenacityPropertyKey, TenacityCommandProperties.Setter>of(key, TenacityCommandProperties.build(configuration.getTenacityConfiguration()));
        }

        @Override
        public ImmutableMap<TenacityPropertyKey, HystrixThreadPoolProperties.Setter> buildThreadpoolProperties() {
            return ImmutableMap.<TenacityPropertyKey, TenacityThreadPoolProperties.Setter>of(key, TenacityThreadPoolProperties.build(configuration.getTenacityConfiguration()));
        }
    }

    private static class OverrideConfiguration extends Configuration {
        private final TenacityConfiguration tenacityConfiguration;

        private OverrideConfiguration(TenacityConfiguration tenacityConfiguration) {
            this.tenacityConfiguration = tenacityConfiguration;
        }

        private TenacityConfiguration getTenacityConfiguration() {
            return tenacityConfiguration;
        }
    }

    @Test
    public void overriddenProperties() throws Exception {
        final OverrideConfiguration exampleConfiguration = new OverrideConfiguration(
                new TenacityConfiguration(
                new ThreadPoolConfiguration(50, 3, 27, 57, 2000, 20),
                new CircuitBreakerConfiguration(1, 2, 3),
                982));
        tenacityPropertyStore = new TenacityPropertyStore(new OverridePropertiesBuilder(DependencyKey.OVERRIDE, exampleConfiguration));

        assertThat(new TenacitySuccessCommand(tenacityPropertyStore, DependencyKey.OVERRIDE).execute()).isEqualTo("value");
        assertThat(new TenacitySuccessCommand(tenacityPropertyStore, DependencyKey.OVERRIDE).queue().get()).isEqualTo("value");

        final HystrixThreadPoolProperties threadPoolProperties = HystrixPropertiesFactory
                .getThreadPoolProperties(HystrixThreadPoolKey.Factory.asKey(DependencyKey.OVERRIDE.toString()), null);

        final ThreadPoolConfiguration threadPoolConfiguration = exampleConfiguration.getTenacityConfiguration().getThreadpool();
        assertEquals(threadPoolProperties.coreSize().get().intValue(), threadPoolConfiguration.getThreadPoolCoreSize());
        assertEquals(threadPoolProperties.keepAliveTimeMinutes().get().intValue(), threadPoolConfiguration.getKeepAliveTimeMinutes());
        assertEquals(threadPoolProperties.maxQueueSize().get().intValue(), threadPoolConfiguration.getMaxQueueSize());
        assertEquals(threadPoolProperties.metricsRollingStatisticalWindowBuckets().get().intValue(), threadPoolConfiguration.getMetricsRollingStatisticalWindowBuckets());
        assertEquals(threadPoolProperties.metricsRollingStatisticalWindowInMilliseconds().get().intValue(), threadPoolConfiguration.getMetricsRollingStatisticalWindowInMilliseconds());
        assertEquals(threadPoolProperties.queueSizeRejectionThreshold().get().intValue(), threadPoolConfiguration.getQueueSizeRejectionThreshold());
    }

    private static class SleepCommand extends TenacityCommand<String> {
        private SleepCommand(TenacityPropertyStore tenacityPropertyStore, TenacityPropertyKey tenacityPropertyKey) {
            super("Test", "Sleep", tenacityPropertyStore, tenacityPropertyKey);
        }

        @Override
        protected String run() throws Exception {
            Thread.sleep(500);
            return "sleep";
        }

        @Override
        protected String getFallback() {
            return "fallback";
        }
    }

    @Test
    public void queueRejection() throws Exception {
        final int queueMaxSize = 5;
        final OverrideConfiguration exampleConfiguration = new OverrideConfiguration(

                new TenacityConfiguration(
                        new ThreadPoolConfiguration(1, 1, 10, queueMaxSize, 10000, 10),
                        new CircuitBreakerConfiguration(20, 5000, 50),
                        5000));
        tenacityPropertyStore = new TenacityPropertyStore(new OverridePropertiesBuilder(DependencyKey.SLEEP, exampleConfiguration));

        final ImmutableList.Builder<Future<String>> sleepCommands = ImmutableList.builder();
        for (int i = 0; i < queueMaxSize * 2; i++) {
            sleepCommands.add(new SleepCommand(tenacityPropertyStore, DependencyKey.SLEEP).queue());
        }

        for (Future<String> future : sleepCommands.build()) {
            assertFalse(future.isCancelled());
            assertThat(future.get()).isInstanceOf(String.class);
        }

        final HystrixCommandMetrics sleepCommandMetrics = HystrixCommandMetrics
                .getInstance(HystrixCommandKey.Factory.asKey("Sleep"));
        assertThat(sleepCommandMetrics
                .getCumulativeCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED))
                .isEqualTo(4);
        assertThat(sleepCommandMetrics
                .getCumulativeCount(HystrixRollingNumberEvent.TIMEOUT))
                .isEqualTo(0);
        assertThat(sleepCommandMetrics
                .getCumulativeCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS))
                .isEqualTo(4);
        assertThat(sleepCommandMetrics
                .getCumulativeCount(HystrixRollingNumberEvent.SHORT_CIRCUITED))
                .isEqualTo(0);

        final HystrixThreadPoolProperties threadPoolProperties = HystrixPropertiesFactory
                .getThreadPoolProperties(HystrixThreadPoolKey.Factory.asKey(DependencyKey.SLEEP.toString()), null);

        final ThreadPoolConfiguration threadPoolConfiguration = exampleConfiguration.getTenacityConfiguration().getThreadpool();
        assertEquals(threadPoolProperties.queueSizeRejectionThreshold().get().intValue(), threadPoolConfiguration.getQueueSizeRejectionThreshold());
        assertEquals(threadPoolProperties.maxQueueSize().get().intValue(), threadPoolConfiguration.getMaxQueueSize());
    }
}