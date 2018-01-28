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

import static com.sonian.samza.k8s.KubernetesConfig.JOB_CONTAINER_PORTS;
import static com.sonian.samza.k8s.KubernetesConfig.JOB_CONTAINER_RAM;
import static com.sonian.samza.k8s.KubernetesConfig.JOB_CONTAINER_RUNAS;
import static com.sonian.samza.k8s.KubernetesConfig.K8S_POD_LABELS;

import static org.hamcrest.CoreMatchers.hasItems;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import java.util.Map;

import org.apache.samza.config.Config;
import org.apache.samza.config.MapConfig;
import org.junit.Test;

import com.google.common.collect.ImmutableMap;

import io.kubernetes.client.models.V1ContainerPort;
import io.kubernetes.client.models.V1PodSecurityContext;

public class KubernetesConfigTest {

  @Test
  public void testPodLabels() {
    final Config c = new MapConfig(ImmutableMap.of(K8S_POD_LABELS, "app:foo,run:bar"));
    final KubernetesConfig kc = new KubernetesConfig(c);

    assertEquals(ImmutableMap.of("app", "foo", "run", "bar"), kc.getKubernetesPodLabels());
  }

  @Test
  public void testPodPorts() {
    final Config c = new MapConfig(ImmutableMap.of(JOB_CONTAINER_PORTS, "80,443"));
    final KubernetesConfig kc = new KubernetesConfig(c);
    final V1ContainerPort p0 = new V1ContainerPort();
    final V1ContainerPort p1 = new V1ContainerPort();

    p0.setContainerPort(80);
    p1.setContainerPort(443);

    assertThat(kc.getKubernetesPodPorts(), hasItems(p0, p1));
  }

  @Test
  public void testResourceReqs() {
    final Config c = new MapConfig(ImmutableMap.of(JOB_CONTAINER_RAM, "256Mi"));
    final KubernetesConfig kc = new KubernetesConfig(c);

    final Map<String, String> res = kc.getResourceReqs().getRequests();

    assertEquals("1", res.get("cpu"));
    assertEquals("256Mi", res.get("memory"));
  }

  @Test
  public void testSecurityContext() {
    final Config c0 = new MapConfig();
    final Config c1 = new MapConfig(ImmutableMap.of(JOB_CONTAINER_RUNAS, "1000"));
    final Config c2 = new MapConfig(ImmutableMap.of(JOB_CONTAINER_RUNAS, ":1000"));
    final Config c3 = new MapConfig(ImmutableMap.of(JOB_CONTAINER_RUNAS, "1000:1000"));
    final Long onek = new Long(1000);
    KubernetesConfig kc;
    V1PodSecurityContext sc;

    kc = new KubernetesConfig(c0);
    sc = kc.getSecurityContext();
    assertEquals(null, sc.getRunAsUser());
    assertEquals(null, sc.getFsGroup());

    kc = new KubernetesConfig(c1);
    sc = kc.getSecurityContext();
    assertEquals(onek, sc.getRunAsUser());
    assertEquals(null, sc.getFsGroup());

    kc = new KubernetesConfig(c2);
    sc = kc.getSecurityContext();
    assertEquals(null, sc.getRunAsUser());
    assertEquals(onek, sc.getFsGroup());

    kc = new KubernetesConfig(c3);
    sc = kc.getSecurityContext();
    assertEquals(onek, sc.getRunAsUser());
    assertEquals(onek, sc.getFsGroup());
  }

}
