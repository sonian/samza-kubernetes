Samza + Kubernetes = <3
=======================

This repository contains a library and example project for deploying Samza
applications to Kubernetes.

Requirements
------------

The library requires **Java 8+** to compile and connectivity to **Kubernetes
1.8+** to create the necessary resources.

Overview
--------

This repository contains a library and example application for running Samza on
Kubernetes using the low- and high-level APIs, respectively. The next two
sections explain these components.

Running Low-level Jobs
~~~~~~~~~~~~~~~~~~~~~~

**NOTE: This integration is incomplete.**

The low-level API is composed of Jobs and Tasks, with Containers and
Coordinators playing a management role. A Job is composed of a StreamJobFactory
and StreamJob, which together are responsible for launching the Job into a
target environment, waiting for a ready-state, then exiting.

This job-launching logic is handled by ``KubernetesJob`` which reads the
supplied configuration file and uses the Kubernetes API to create a
``StatefulSet`` for the target job.

Configuration Options
+++++++++++++++++++++

The following options can be used to influence the template of the resulting
``StatefulSet``.

+------------------------------+---------------------------------------------------------------------------------------------------------------+--------------+
| Option                       | Description                                                                                                   | Default      |
+==============================+===============================================================================================================+==============+
| ``job.name``                 | Same as Samza                                                                                                 | **REQUIRED** |
+------------------------------+---------------------------------------------------------------------------------------------------------------+--------------+
| ``job.id``                   | Same as Samza                                                                                                 | 1            |
+------------------------------+---------------------------------------------------------------------------------------------------------------+--------------+
| ``job.container.count``      | Number of Docker containers to launch; should be <= maximum tasks based on your stream partitions             | 1            |
+------------------------------+---------------------------------------------------------------------------------------------------------------+--------------+
| ``job.container.image``      | Name and (optional) tag of a Docker container capable of running your Samza Container                         | **REQUIRED** |
+------------------------------+---------------------------------------------------------------------------------------------------------------+--------------+
| ``job.container.cpus``       | Requested CPUs for each container                                                                             | 1            |
+------------------------------+---------------------------------------------------------------------------------------------------------------+--------------+
| ``job.container.ram``        | Required RAM for  each container                                                                              | 128Mi        |
+------------------------------+---------------------------------------------------------------------------------------------------------------+--------------+
| ``job.container.runas``      | A ``user:group`` ID-pair for the container's security context; also accepts ``user:`` or ``:group`` format    | N/A          |
+------------------------------+---------------------------------------------------------------------------------------------------------------+--------------+
| ``job.container.ports``      | Comma-delimited list of container ports                                                                       | N/A          |
+------------------------------+---------------------------------------------------------------------------------------------------------------+--------------+
| ``kubernetes.api.key``       | Key used for ``BearerToken`` authentication; if not  present, assumes no authentication                       | N/A          |
+------------------------------+---------------------------------------------------------------------------------------------------------------+--------------+
| ``kubernetes.api.host``      | Kubernetes API host                                                                                           | localhost    |
+------------------------------+---------------------------------------------------------------------------------------------------------------+--------------+
| ``kubernetes.api.port``      | Kubernetes API port                                                                                           | 9090         |
+------------------------------+---------------------------------------------------------------------------------------------------------------+--------------+
| ``kubernetes.api.namespace`` | An **existing** namespace under which Kubernetes resources will be created                                    | default      |
+------------------------------+---------------------------------------------------------------------------------------------------------------+--------------+
| ``kubernetes.pod.labels``    | key-value pairs of labels to apply to Pods in the form ``name:value[,name:value,...]                          | N/A          |
+------------------------------+---------------------------------------------------------------------------------------------------------------+--------------+
| ``task.state.enabled``       | If enabled, a persistent volume template will be mounted in each container at ``/samza/state`` for KV storage | true         |
+------------------------------+---------------------------------------------------------------------------------------------------------------+--------------+
| ``task.state.storage-class`` | Storage class for KV storage (you should specify SSD if you're using RocksDB)                                 | standard     |
+------------------------------+---------------------------------------------------------------------------------------------------------------+--------------+
| ``task.state.size``          | Size of storage to request                                                                                    | 1Gi          |
+------------------------------+---------------------------------------------------------------------------------------------------------------+--------------+

Your Job Image
++++++++++++++

The incomplete part of this integration is a suitable template for the
``job.container.image``. My current thoughts on this are:

#. We likely want to use ``LocalContainerRunner`` with ``run-container.sh`` as an entrypoint.
#. This would also require a coordinator, likely in the form of ``ZkJobCoordinator``.
#. ``KubernetesJob`` could configure the coordinator container generically,
   isolating ``job.container.image`` as the user's only responsibility
#. ``KubernetesJob`` already exports ``SAMZA_CONTAINER_ID`` and
   ``SAMZA_COORDINATOR_SYSTEM_CONFIG`` which should be sufficient to bootstrap
   the components in (1) and (2)

Hopefully with some guidance from the Samza developers, I can complete the
low-level API integration. Until then, I recommend (and will be using) the
high-level API that abstracts most of these details.


Running High-level Applications
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

By comparison, using the high-level ``StreamApplication`` API is considerably
simpler. There's actually very little to it: just package the application in a
way that can be executed and deploy it using ``StatefulSet`` directly, instead
of via the ``KubernetesJob``.

Running ``gradle dockerDistTar`` will produce a folder ``example/build/docker``
which contains a Dockerfile and everything necessary to run the example
application in Kubernetes. Running ``gradle dockerBuildImage`` will install said
image locally, at which point you can run ``kubectl create -f example/k8s/app.yaml``

.. warning::

   If you're planning to use the KV store with a volume mount, keep in mind that
   Samza hard-codes the location to ``{user.dir}/state``. The combination of the
   generated Docker image and ``app.yaml`` mount point works because the image
   runs the application from ``/`` and the mount point is ``/state``.


License
-------

Licensed under the Apache License, Version 2.0.


.. _`Kubernetes Job`: https://kubernetes.io/docs/concepts/workloads/controllers/jobs-run-to-completion/
