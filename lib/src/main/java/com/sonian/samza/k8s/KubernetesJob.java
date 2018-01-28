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

import java.text.MessageFormat;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.samza.job.ApplicationStatus;
import org.apache.samza.job.StreamJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import io.kubernetes.client.ApiCallback;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.models.V1Container;
import io.kubernetes.client.models.V1DeleteOptions;
import io.kubernetes.client.models.V1LabelSelector;
import io.kubernetes.client.models.V1ObjectMeta;
import io.kubernetes.client.models.V1PersistentVolumeClaim;
import io.kubernetes.client.models.V1PersistentVolumeClaimSpec;
import io.kubernetes.client.models.V1PodSpec;
import io.kubernetes.client.models.V1PodTemplateSpec;
import io.kubernetes.client.models.V1ResourceRequirements;
import io.kubernetes.client.models.V1Status;
import io.kubernetes.client.models.V1VolumeMount;
import io.kubernetes.client.models.V1beta2StatefulSet;
import io.kubernetes.client.models.V1beta2StatefulSetSpec;

/**
 * StreamJob that launches a Kubernetes StatefulSet according to KubernetesConfig.
 *
 * Consult the README for a full list of supported configuration options or see
 * {@link KubernetesConfig}.
 *
 */
public class KubernetesJob implements StreamJob {
  private static final Logger log = LoggerFactory.getLogger(KubernetesJob.class);

  static final String STATE_LABEL = "kv-store";
  static final String STATE_PATH = "/state";

  private final KubernetesConfig config;

  private ApplicationStatus currentStatus = ApplicationStatus.New;

  KubernetesJob(KubernetesConfig config) {
    this.config = config;
  }

  @Override
  public StreamJob submit() {
    final String ns = config.getKubernetesApiNamespace();
    final V1beta2StatefulSet body = new V1beta2StatefulSet();
    final V1ObjectMeta metadata = new V1ObjectMeta();
    final V1ObjectMeta tplMetadata = new V1ObjectMeta();
    final Map<String, String> labels = new HashMap<>();
    final V1beta2StatefulSetSpec spec = new V1beta2StatefulSetSpec();
    final V1PodSpec podSpec = new V1PodSpec();
    final V1LabelSelector selector = new V1LabelSelector();
    final V1PodTemplateSpec template = new V1PodTemplateSpec();
    final V1Container con = new V1Container();

    labels.put("job", config.getJobName());
    labels.put("framework", "samza");
    labels.putAll(config.getKubernetesPodLabels());

    metadata.setNamespace(ns);
    metadata.setName(getName());
    metadata.setLabels(labels);

    selector.setMatchLabels(labels);

    spec.setReplicas(config.getContainerCount());
    spec.setSelector(selector);
    spec.setTemplate(template);
    if (config.wantsTaskState()) {
      final V1PersistentVolumeClaim pvc = new V1PersistentVolumeClaim();
      final V1ObjectMeta pom = new V1ObjectMeta();
      final V1PersistentVolumeClaimSpec pvSpec = new V1PersistentVolumeClaimSpec();
      final V1ResourceRequirements pvReqs = new V1ResourceRequirements();

      pom.setName(STATE_LABEL);
      pvReqs.setRequests(ImmutableMap.of("storage", config.getTaskStateSize()));
      pvSpec.addAccessModesItem("ReadWriteOnce");
      pvSpec.setResources(pvReqs);
      pvSpec.setStorageClassName(config.getTaskStateClass());

      pvc.setMetadata(pom);
      pvc.setSpec(pvSpec);
      spec.addVolumeClaimTemplatesItem(pvc);
    }

    con.setName(getName());
    con.setImage(config.getContainerImage());
    con.setPorts(config.getKubernetesPodPorts());
    con.setResources(config.getResourceReqs());
    if (config.wantsTaskState()) {
      final V1VolumeMount v = new V1VolumeMount();
      v.setName(STATE_LABEL);
      v.setMountPath(STATE_PATH);
      con.addVolumeMountsItem(v);
    }

    podSpec.addContainersItem(con);
    podSpec.setSecurityContext(config.getSecurityContext());

    tplMetadata.setLabels(labels);

    template.setSpec(podSpec);
    template.setMetadata(tplMetadata);

    body.setSpec(spec);
    body.setMetadata(metadata);
    body.setMetadata(metadata);
    body.setSpec(spec);

    try {
      config.getClient().createNamespacedStatefulSetAsync(ns, body, null, deployCallback);
    } catch (ApiException e) {
      deployCallback.onFailure(e, e.getCode(), e.getResponseHeaders());
    }
    return this;
  }

  private String getName() {
    return "job_" + config.getJobName() + "_" + config.getJobId();
  }

  @Override
  public StreamJob kill() {
    final V1DeleteOptions deleteOptions = new V1DeleteOptions();
    try {
      config.getClient().deleteNamespacedDeploymentAsync(getName(),
          config.getKubernetesApiNamespace(), deleteOptions, null, 60, null, "Foreground",
          deleteCallback);
    } catch (ApiException e) {
      deleteCallback.onFailure(e, e.getCode(), e.getResponseHeaders());
    }
    return this;
  }

  @Override
  public ApplicationStatus waitForFinish(long timeoutMs) {
    return waitForAnyStatus(
        ImmutableList.of(ApplicationStatus.SuccessfulFinish, ApplicationStatus.UnsuccessfulFinish),
        timeoutMs);
  }

  @Override
  public ApplicationStatus waitForStatus(ApplicationStatus status, long timeoutMs) {
    return waitForAnyStatus(ImmutableList.of(status), timeoutMs);
  }

  ApplicationStatus waitForAnyStatus(Collection<ApplicationStatus> status, long timeoutMs) {
    final long startTimeMs = System.currentTimeMillis();

    while (System.currentTimeMillis() - startTimeMs < timeoutMs) {
      if (status.contains(currentStatus)) {
        return currentStatus;
      }

      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
        return currentStatus;
      }
    }
    return currentStatus;
  }

  @Override
  public ApplicationStatus getStatus() {
    return currentStatus;
  }

  private final ApiCallback<V1beta2StatefulSet> deployCallback =
      new ApiCallback<V1beta2StatefulSet>() {

        @Override
        public void onFailure(ApiException e, int statusCode,
            Map<String, List<String>> responseHeaders) {
          currentStatus = ApplicationStatus.unsuccessfulFinish(e);
          log.error(MessageFormat.format("Failed starting job {0}: {1} ({2})", getName(),
              e.getMessage(), e.getCode()), e.getCause());
        }

        @Override
        public void onSuccess(V1beta2StatefulSet result, int statusCode,
            Map<String, List<String>> responseHeaders) {
          currentStatus = ApplicationStatus.Running;
        }

        @Override
        public void onUploadProgress(long bytesWritten, long contentLength, boolean done) {}

        @Override
        public void onDownloadProgress(long bytesRead, long contentLength, boolean done) {}
      };

  private final ApiCallback<V1Status> deleteCallback = new ApiCallback<V1Status>() {

    @Override
    public void onFailure(ApiException e, int statusCode,
        Map<String, List<String>> responseHeaders) {
      currentStatus = ApplicationStatus.unsuccessfulFinish(e.getCause());
      log.error(MessageFormat.format("Failed killing job {0}: {1} ({2})", getName(), e.getMessage(),
          e.getCode()), e.getCause());
    }

    @Override
    public void onSuccess(V1Status result, int statusCode,
        Map<String, List<String>> responseHeaders) {
      currentStatus = ApplicationStatus.SuccessfulFinish;
    }

    @Override
    public void onUploadProgress(long bytesWritten, long contentLength, boolean done) {}

    @Override
    public void onDownloadProgress(long bytesRead, long contentLength, boolean done) {}

  };

}
