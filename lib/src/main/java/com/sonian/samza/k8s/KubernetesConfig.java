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

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.samza.config.Config;
import org.apache.samza.config.ConfigException;
import org.apache.samza.config.ShellCommandConfig;
import org.apache.samza.serializers.model.SamzaObjectMapper;
import org.apache.samza.util.Util;

import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;

import io.kubernetes.client.ApiClient;
import io.kubernetes.client.apis.AppsV1beta2Api;
import io.kubernetes.client.models.V1ContainerPort;
import io.kubernetes.client.models.V1EnvVar;
import io.kubernetes.client.models.V1EnvVarSource;
import io.kubernetes.client.models.V1ObjectFieldSelector;
import io.kubernetes.client.models.V1PodSecurityContext;
import io.kubernetes.client.models.V1ResourceRequirements;

/**
 * Wraps a Samza Config with Kubernetes-specific options.
 */
public class KubernetesConfig {

  private final Config config;

  static String JOB_NAME = "job.name";
  static String JOB_ID = "job.id";
  static String TASK_STATE = "task.state.enabled";
  static String TASK_STATE_CLASS = "task.state.storage-class";
  static String TASK_STATE_SIZE = "task.state.size";
  static String JOB_CONTAINER_COUNT = "job.container.count";
  static String JOB_CONTAINER_IMAGE = "job.container.image";
  static String JOB_CONTAINER_CPUS = "job.container.cpus";
  static String JOB_CONTAINER_RAM = "job.container.ram";
  static String JOB_CONTAINER_RUNAS = "job.container.runas";
  static String JOB_CONTAINER_PORTS = "job.container.ports";
  static String K8S_API_KEY = "kubernetes.api.key";
  static String K8S_API_HOST = "kubernetes.api.host";
  static String K8S_API_PORT = "kubernetes.api.port";
  static String K8S_API_NAMESPACE = "kubernetes.api.namespace";
  static String K8S_POD_LABELS = "kubernetes.pod.labels";

  public KubernetesConfig(Config config) {
    this.config = config;
  }

  public String getJobName() {
    return config.get(JOB_NAME);
  }

  public int getJobId() {
    return config.getInt(JOB_ID, 1);
  }

  public int getContainerCount() {
    return config.getInt(JOB_CONTAINER_COUNT, 1);
  }

  public String getContainerImage() {
    return config.get(JOB_CONTAINER_IMAGE);
  }

  public Optional<String> getKubernetesApiKey() {
    return Optional.ofNullable(config.get(K8S_API_KEY));
  }

  public String getKubernetesApiNamespace() {
    return config.get(K8S_API_NAMESPACE);
  }

  public String getKubernetesApiHost() {
    return config.get(K8S_API_HOST, "localhost");
  }

  public String getKubernetesApiPort() {
    return config.get(K8S_API_PORT, "9090");
  }

  public Map<String, String> getKubernetesPodLabels() {
    final String s = config.get(K8S_POD_LABELS, "");
    return Splitter.on(",").omitEmptyStrings().withKeyValueSeparator(":").split(s);
  }

  public List<V1ContainerPort> getKubernetesPodPorts() {
    final String s = config.get(JOB_CONTAINER_PORTS, "");
    return Splitter.on(",").omitEmptyStrings().splitToList(s).stream().<V1ContainerPort>map(v -> {
      V1ContainerPort p = new V1ContainerPort();
      p.setContainerPort(Integer.parseInt(v));
      return p;
    }).collect(Collectors.toList());
  }

  public V1ResourceRequirements getResourceReqs() {
    final String cpus = config.get(JOB_CONTAINER_CPUS, "1");
    final String ram = config.get(JOB_CONTAINER_RAM, "128Mi");
    final V1ResourceRequirements vrr = new V1ResourceRequirements();

    vrr.putRequestsItem("cpu", cpus);
    vrr.putRequestsItem("memory", ram);

    return vrr;
  }

  public V1PodSecurityContext getSecurityContext() {
    final V1PodSecurityContext sc = new V1PodSecurityContext();
    final List<String> pair = Splitter.on(":").splitToList(config.get(JOB_CONTAINER_RUNAS, ""));

    if (pair.size() > 0 && !Strings.isNullOrEmpty(pair.get(0))) {
      sc.setRunAsUser(Long.parseLong(pair.get(0)));
    }

    if (pair.size() > 1 && !Strings.isNullOrEmpty(pair.get(1))) {
      sc.setFsGroup(Long.parseLong(pair.get(1)));
    }

    return sc;
  }

  public boolean wantsTaskState() {
    return config.getBoolean(TASK_STATE, true);
  }

  public String getTaskStateClass() {
    return config.get(TASK_STATE_CLASS, "standard");
  }

  public String getTaskStateSize() {
    return config.get(TASK_STATE_SIZE, "1Gi");
  }

  public List<V1EnvVar> getEnv() {
    final Config csc = Util.buildCoordinatorStreamConfig(config);
    try {
      final String json =
          Util.envVarEscape(SamzaObjectMapper.getObjectMapper().writeValueAsString(csc));
      final V1EnvVar envCsc = new V1EnvVar();
      final V1EnvVar envCid = new V1EnvVar();
      final V1EnvVarSource from = new V1EnvVarSource();
      final V1ObjectFieldSelector ofs = new V1ObjectFieldSelector();

      envCsc.setName(ShellCommandConfig.ENV_COORDINATOR_SYSTEM_CONFIG());
      envCsc.setValue(json);

      ofs.setFieldPath("metadata.name");
      from.setFieldRef(ofs);

      envCid.setName(ShellCommandConfig.ENV_CONTAINER_ID());
      envCid.setValueFrom(from);

      return ImmutableList.of(envCsc, envCid);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

  }

  public AppsV1beta2Api getClient() {
    ApiClient client = new ApiClient();
    client.setBasePath("https://" + getKubernetesApiHost() + ":" + getKubernetesApiPort());
    getKubernetesApiKey().ifPresent(key -> client.setApiKey(key));
    return new AppsV1beta2Api(client);
  }

  private void validate() throws ConfigException {
    if (getContainerImage() == null) {
      throw new ConfigException("job.container.image is required!");
    }
  }

  public static KubernetesConfig fromValidatedConfig(Config config) throws ConfigException {
    KubernetesConfig kc = new KubernetesConfig(config);
    kc.validate();
    return kc;
  }

}
