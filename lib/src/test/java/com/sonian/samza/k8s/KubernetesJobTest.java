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

import static com.sonian.samza.k8s.KubernetesConfig.JOB_CONTAINER_COUNT;
import static com.sonian.samza.k8s.KubernetesConfig.JOB_CONTAINER_IMAGE;
import static com.sonian.samza.k8s.KubernetesConfig.JOB_CONTAINER_PORTS;
import static com.sonian.samza.k8s.KubernetesConfig.JOB_CONTAINER_RUNAS;
import static com.sonian.samza.k8s.KubernetesConfig.JOB_NAME;
import static com.sonian.samza.k8s.KubernetesConfig.K8S_API_NAMESPACE;
import static com.sonian.samza.k8s.KubernetesConfig.K8S_POD_LABELS;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.samza.config.Config;
import org.apache.samza.config.MapConfig;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.google.common.collect.ImmutableMap;

import io.kubernetes.client.ApiCallback;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.AppsV1beta2Api;
import io.kubernetes.client.models.V1Container;
import io.kubernetes.client.models.V1ObjectMeta;
import io.kubernetes.client.models.V1PersistentVolumeClaim;
import io.kubernetes.client.models.V1PersistentVolumeClaimSpec;
import io.kubernetes.client.models.V1PodSecurityContext;
import io.kubernetes.client.models.V1beta2StatefulSet;
import io.kubernetes.client.models.V1beta2StatefulSetSpec;

@RunWith(MockitoJUnitRunner.StrictStubs.class)
public class KubernetesJobTest {

  @Mock
  private AppsV1beta2Api client;

  @SuppressWarnings("unchecked")
  @Test
  public void testSubmit() throws ApiException {
    final ArgumentCaptor<V1beta2StatefulSet> deploy =
        ArgumentCaptor.forClass(V1beta2StatefulSet.class);
    final ArgumentCaptor<ApiCallback<?>> callback = ArgumentCaptor.forClass(ApiCallback.class);
    final Config c = new MapConfig(ImmutableMap.<String, String>builder().put(JOB_NAME, "test-job")
        .put(JOB_CONTAINER_IMAGE, "test-image:v1").put(JOB_CONTAINER_RUNAS, "1000:1000")
        .put(K8S_API_NAMESPACE, "test-ns").put(K8S_POD_LABELS, "run:me")
        .put(JOB_CONTAINER_PORTS, "80,443").put(JOB_CONTAINER_COUNT, "2").build());
    final KubernetesConfig kc = spy(new KubernetesConfig(c));
    doReturn(client).when(kc).getClient();

    final KubernetesJob kj = new KubernetesJob(kc);
    kj.submit();

    verify(client).createNamespacedStatefulSetAsync(eq("test-ns"), deploy.capture(), isNull(),
        (ApiCallback<V1beta2StatefulSet>) callback.capture());

    final V1ObjectMeta meta = deploy.getValue().getMetadata();
    assertEquals("job_test-job_1", meta.getName());
    assertEquals(ImmutableMap.of("job", "test-job", "framework", "samza", "run", "me"),
        meta.getLabels());

    final V1beta2StatefulSetSpec spec = deploy.getValue().getSpec();
    final V1PersistentVolumeClaim pv = spec.getVolumeClaimTemplates().get(0);
    assertThat(pv, not(nullValue()));
    final V1PersistentVolumeClaimSpec pvSpec = pv.getSpec();
    assertThat(pvSpec, not(nullValue()));
    final Map<String, String> pvReqs = pvSpec.getResources().getRequests();
    assertEquals(KubernetesJob.STATE_LABEL, pv.getMetadata().getName());
    assertEquals("ReadWriteOnce", pvSpec.getAccessModes().get(0));
    assertEquals(pvReqs.get("storage"), "1Gi");
    assertEquals(2, (int) spec.getReplicas());

    final V1PodSecurityContext security = spec.getTemplate().getSpec().getSecurityContext();
    assertEquals(1000, (long) security.getRunAsUser());
    assertEquals(1000, (long) security.getFsGroup());

    final V1Container con = spec.getTemplate().getSpec().getContainers().get(0);
    final List<Integer> ports = con.getPorts().stream().<Integer>map(cp -> cp.getContainerPort())
        .collect(Collectors.toList());
    assertEquals("job_test-job_1", con.getName());
    assertEquals("test-image:v1", con.getImage());
    assertEquals(ImmutableMap.of("cpu", "1", "memory", "128Mi"), con.getResources().getRequests());
    assertThat(ports, hasItems(80, 443));
  }
}
