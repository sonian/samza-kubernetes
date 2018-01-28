/*
 * Copyright 2018 Barracuda, Inc.

 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at

 *   http://www.apache.org/licenses/LICENSE-2.0

 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sonian.samza.k8s;

import org.apache.samza.config.Config;
import org.apache.samza.job.StreamJob;
import org.apache.samza.job.StreamJobFactory;

public class KubernetesJobFactory implements StreamJobFactory {

  /*
   * (non-Javadoc)
   *
   * @see org.apache.samza.job.StreamJobFactory#getJob(org.apache.samza.config.Config)
   */
  @Override
  public StreamJob getJob(Config config) {
    return new KubernetesJob(KubernetesConfig.fromValidatedConfig(config));
  }

}
