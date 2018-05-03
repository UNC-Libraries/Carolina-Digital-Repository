/**
 * Copyright 2008 The University of North Carolina at Chapel Hill
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.unc.lib.dl.cdr.services.metrics;

import io.dropwizard.metrics5.MetricName;
import io.dropwizard.metrics5.MetricRegistry;
import io.dropwizard.metrics5.Timer;

/**
 * A factory for creating class-specific timers for gathering metrics
 *
 * @author harring
 *
 */
public class TimerFactory {

    private static final RegistryService registryService = RegistryService.getInstance();

    private TimerFactory() {
    }

    public static Timer createTimerForClass(Class<?> metricNameClass) {
        MetricName requests = MetricRegistry.name(metricNameClass, "requests", "number-and-duration");
        Timer timer = registryService.getRegistry().timer (requests);

        return timer;
    }

}
