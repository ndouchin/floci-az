package io.floci.az.services.aks;

import io.floci.az.services.aks.AksVersions.Version;

import java.util.ArrayList;
import java.util.List;

/**
 * The storage classes AKS creates, as a k3s auto-deploy manifest. Each Azure CSI driver name
 * ({@code disk.csi.azure.com}, {@code file.csi.azure.com}) is served by a local-path-provisioner registered under
 * that name, so the classes' provisioner, default flag, binding mode and expansion flag match AKS and their claims
 * bind. Volumes live on a Docker volume every node mounts, so they survive a node being recreated and a file share
 * is shared across nodes.
 */
final class AksStorageManifests {

    static final String FILE_NAME = "floci-aks-storage.yaml";
    static final String PROVISIONER_IMAGE = "rancher/local-path-provisioner:v0.0.37";
    static final String HELPER_IMAGE = "rancher/mirrored-library-busybox:1.37.0";
    private static final Version PREMIUM_V2_SINCE = new Version(1, 35, 0);

    private record StorageClass(String name, String provisioner, String bindingMode, List<String> parameters,
                                boolean isDefault) {
    }

    private AksStorageManifests() {
    }

    static String manifest(Version controlPlane) {
        List<String> documents = new ArrayList<>();
        documents.add(rbac());
        documents.addAll(provisioner("azuredisk", "disk.csi.azure.com", "disk"));
        documents.addAll(provisioner("azurefile", "file.csi.azure.com", "file"));
        for (StorageClass storageClass : storageClasses(controlPlane)) {
            documents.add(storageClass(storageClass));
        }
        return String.join("\n---\n", documents) + "\n";
    }

    private static List<StorageClass> storageClasses(Version controlPlane) {
        String disk = "disk.csi.azure.com";
        String file = "file.csi.azure.com";
        String wait = "WaitForFirstConsumer";
        String immediate = "Immediate";
        List<StorageClass> classes = new ArrayList<>(List.of(
                new StorageClass("default", disk, wait, List.of("skuname: StandardSSD_LRS"), true),
                new StorageClass("managed-csi", disk, wait, List.of("skuname: StandardSSD_LRS"), false),
                new StorageClass("managed-csi-premium", disk, wait, List.of("skuname: Premium_LRS"), false)));
        if (controlPlane.compareTo(PREMIUM_V2_SINCE) >= 0) {
            classes.add(new StorageClass("managed-csi-premium-v2", disk, wait,
                    List.of("skuname: PremiumV2_LRS", "cachingMode: None"), false));
        }
        classes.addAll(List.of(
                new StorageClass("managed-premium", disk, wait, List.of("storageaccounttype: Premium_LRS"), false),
                new StorageClass("azurefile-csi", file, immediate, List.of("skuName: Standard_LRS"), false),
                new StorageClass("azurefile-csi-premium", file, immediate, List.of("skuName: Premium_LRS"), false),
                new StorageClass("azurefile", file, immediate, List.of("skuName: Standard_LRS"), false),
                new StorageClass("azurefile-premium", file, immediate, List.of("skuName: Premium_LRS"), false)));
        return classes;
    }

    private static String storageClass(StorageClass sc) {
        StringBuilder yaml = new StringBuilder("apiVersion: storage.k8s.io/v1\nkind: StorageClass\nmetadata:\n");
        if (sc.isDefault()) {
            yaml.append("  annotations:\n    storageclass.kubernetes.io/is-default-class: \"true\"\n");
        }
        yaml.append("  name: ").append(sc.name()).append("\n")
                .append("  labels:\n    kubernetes.io/cluster-service: \"true\"\n")
                .append("provisioner: ").append(sc.provisioner()).append("\n")
                .append("parameters:\n");
        sc.parameters().forEach(p -> yaml.append("  ").append(p).append("\n"));
        yaml.append("reclaimPolicy: Delete\n")
                .append("volumeBindingMode: ").append(sc.bindingMode()).append("\n")
                .append("allowVolumeExpansion: true");
        return yaml.toString();
    }

    private static String rbac() {
        return """
                apiVersion: v1
                kind: ServiceAccount
                metadata:
                  name: floci-azure-csi-provisioner
                  namespace: kube-system
                ---
                apiVersion: rbac.authorization.k8s.io/v1
                kind: ClusterRole
                metadata:
                  name: floci-azure-csi-provisioner
                rules:
                - apiGroups: [""]
                  resources: ["nodes", "persistentvolumeclaims", "configmaps", "pods/log"]
                  verbs: ["get", "list", "watch"]
                - apiGroups: [""]
                  resources: ["endpoints", "persistentvolumes", "pods"]
                  verbs: ["*"]
                - apiGroups: [""]
                  resources: ["events"]
                  verbs: ["create", "patch"]
                - apiGroups: ["storage.k8s.io"]
                  resources: ["storageclasses"]
                  verbs: ["get", "list", "watch"]
                - apiGroups: ["coordination.k8s.io"]
                  resources: ["leases"]
                  verbs: ["get", "list", "watch", "create", "update", "patch"]
                ---
                apiVersion: rbac.authorization.k8s.io/v1
                kind: ClusterRoleBinding
                metadata:
                  name: floci-azure-csi-provisioner
                roleRef:
                  apiGroup: rbac.authorization.k8s.io
                  kind: ClusterRole
                  name: floci-azure-csi-provisioner
                subjects:
                - kind: ServiceAccount
                  name: floci-azure-csi-provisioner
                  namespace: kube-system""";
    }

    private static List<String> provisioner(String name, String driver, String directory) {
        String configMap = "floci-" + name + "-config";
        String config = """
                apiVersion: v1
                kind: ConfigMap
                metadata:
                  name: %s
                  namespace: kube-system
                data:
                  config.json: |-
                    {
                      "sharedFileSystemPath": "%s/%s"
                    }
                  setup: |-
                    #!/bin/sh
                    set -eu
                    mkdir -m 0777 -p "${VOL_DIR}"
                  teardown: |-
                    #!/bin/sh
                    set -eu
                    rm -rf "${VOL_DIR}"
                  helperPod.yaml: |-
                    apiVersion: v1
                    kind: Pod
                    metadata:
                      name: helper-pod
                    spec:
                      priorityClassName: system-node-critical
                      tolerations:
                      - operator: Exists
                      containers:
                      - name: helper-pod
                        image: %s
                        imagePullPolicy: IfNotPresent""".formatted(configMap, AksClusterManager.STORAGE_DIR, directory,
                HELPER_IMAGE);
        String deployment = """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: floci-%s-provisioner
                  namespace: kube-system
                spec:
                  replicas: 1
                  revisionHistoryLimit: 0
                  selector:
                    matchLabels:
                      app: floci-%s-provisioner
                  template:
                    metadata:
                      labels:
                        app: floci-%s-provisioner
                    spec:
                      priorityClassName: system-cluster-critical
                      serviceAccountName: floci-azure-csi-provisioner
                      tolerations:
                      - key: CriticalAddonsOnly
                        operator: Exists
                      nodeSelector:
                        kubernetes.io/os: linux
                      containers:
                      - name: provisioner
                        image: %s
                        imagePullPolicy: IfNotPresent
                        command:
                        - local-path-provisioner
                        - start
                        - --config
                        - /etc/config/config.json
                        - --provisioner-name
                        - %s
                        - --configmap-name
                        - %s
                        - --service-account-name
                        - floci-azure-csi-provisioner
                        volumeMounts:
                        - name: config-volume
                          mountPath: /etc/config/
                        env:
                        - name: POD_NAMESPACE
                          valueFrom:
                            fieldRef:
                              fieldPath: metadata.namespace
                        - name: CONFIG_MOUNT_PATH
                          value: /etc/config/
                      volumes:
                      - name: config-volume
                        configMap:
                          name: %s""".formatted(name, name, name, PROVISIONER_IMAGE, driver, configMap, configMap);
        return List.of(config, deployment);
    }
}
