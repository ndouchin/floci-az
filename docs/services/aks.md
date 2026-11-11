# Azure Kubernetes Service (AKS)

Compatible with the `azure-mgmt-containerservice` SDK, the `az aks` CLI, Terraform's `azurerm_kubernetes_cluster`, and any ARM-speaking client.

> **Requires Docker** (in real mode): each AKS cluster is a k3s server container playing the managed control plane,
> plus one k3s agent container per agent pool node, on a Docker network of its own.
> Set `FLOCI_AZ_SERVICES_AKS_MOCKED=true` for a lightweight mock that skips Docker entirely.

Every AKS name, label, default and error code below was checked against Microsoft's documentation, the AKS REST
specification or the Azure clients' source; the [Sources](#sources) section lists them. Where a behavior could not
be verified, the page says so.

---

## Features

- **Clusters**: CreateOrUpdate, Get, Delete, List (by subscription and by resource group), UpdateTags, `start`, `stop`
- **Long-running operations**: PUT answers `201`/`200` with `Azure-AsyncOperation`; DELETE, `start` and `stop`
  answer `202`. The operation reports `InProgress` until the containers reach what was asked, then `Succeeded`, or
  `Failed` with the cause
- **Kubernetes versions**: `kubernetesVersion` picks a pinned k3s image per minor version; `kubernetesVersions`,
  `upgradeProfiles/default`, `availableAgentPoolVersions` and the agent pool upgrade profile list what the emulator
  runs; upgrades recreate the server on the new image with the same data
- **Agent pools are nodes**: each node is a k3s agent registered under its AKS name, with AKS labels, taints, zones,
  VMSS provider ID, and CPU and memory limits from its VM size; count changes add or remove nodes
- **GPU sizes**: NC, ND and NV nodes carry `kubernetes.azure.com/accelerator=nvidia` and advertise `nvidia.com/gpu`
- **Storage classes** as AKS creates them, with claims that bind
- **Networking**: `networkProfile` pod and service ranges and DNS address applied to k3s, with AKS's defaults
- **Credentials**: `listClusterUserCredential` (`clusterUser`) and `listClusterAdminCredential` (`clusterAdmin`)
  kubeconfigs laid out as AKS writes them; `disableLocalAccounts` refuses the admin one
- **Restore**: in a persistent storage mode, clusters come back at startup on their surviving containers or volumes
- **Mocked mode**: clusters reach `Succeeded` at once with a synthetic kubeconfig; no Docker required

---

## Endpoints

All operations use ARM paths:

```
PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ContainerService/managedClusters/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ContainerService/managedClusters/{name}
DELETE /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ContainerService/managedClusters/{name}
PATCH  /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ContainerService/managedClusters/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ContainerService/managedClusters
GET    /subscriptions/{sub}/providers/Microsoft.ContainerService/managedClusters
GET    /subscriptions/{sub}/providers/Microsoft.ContainerService/locations/{location}/kubernetesVersions
POST   .../managedClusters/{name}/start
POST   .../managedClusters/{name}/stop
POST   .../managedClusters/{name}/listClusterAdminCredential
POST   .../managedClusters/{name}/listClusterUserCredential
GET    .../managedClusters/{name}/upgradeProfiles/default
GET    .../managedClusters/{name}/availableAgentPoolVersions
GET    .../managedClusters/{name}/agentPools
GET    .../managedClusters/{name}/agentPools/{poolName}
PUT    .../managedClusters/{name}/agentPools/{poolName}
DELETE .../managedClusters/{name}/agentPools/{poolName}
GET    .../managedClusters/{name}/agentPools/{poolName}/upgradeProfiles/default
GET    /subscriptions/{sub}/providers/Microsoft.ContainerService/locations/{location}/operations/{id}
GET    /subscriptions/{sub}/providers/Microsoft.ContainerService/locations/{location}/operationresults/{id}
```

---

## Quickstart

### 1: Create a cluster

```bash
curl -s -i -X PUT \
  "http://localhost:4577/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.ContainerService/managedClusters/my-cluster?api-version=2025-10-01" \
  -H "Content-Type: application/json" \
  -d '{
    "location": "eastus",
    "properties": {
      "kubernetesVersion": "1.36",
      "dnsPrefix": "my-cluster-dns",
      "agentPoolProfiles": [
        {"name": "nodepool1", "count": 1, "vmSize": "Standard_DS2_v2", "mode": "System"},
        {"name": "userpool", "count": 2, "vmSize": "Standard_D4s_v5", "mode": "User",
         "nodeLabels": {"team": "payments"}, "nodeTaints": ["dedicated=batch:NoSchedule"],
         "availabilityZones": ["1", "2"]}
      ]
    }
  }'
```

The response is `201` with `provisioningState: Creating` and an `Azure-AsyncOperation` header. In real mode the
cluster reaches `Succeeded` once the API server answers `/readyz` and every node is `Ready` (about 30 to 90 s,
longer when images are pulled). In mocked mode it is `Succeeded` at once.

### 2: Wait for the operation

```bash
OP=$(curl -s -D - -o /dev/null -X PUT ... | awk -F': ' 'tolower($1)=="azure-asyncoperation" {print $2}' | tr -d '\r')
until [ "$(curl -s "$OP" | python3 -c "import sys,json; print(json.load(sys.stdin)['status'])")" != "InProgress" ]; do
  sleep 5
done
```

Clients that poll long-running operations (the SDKs, `az`, Terraform) do this for you.

### 3: Get the kubeconfig

```bash
curl -s -X POST \
  "http://localhost:4577/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.ContainerService/managedClusters/my-cluster/listClusterUserCredential?api-version=2025-10-01" \
  | python3 -c "import sys,json,base64; print(base64.b64decode(json.load(sys.stdin)['kubeconfigs'][0]['value']).decode())" \
  > ~/.kube/my-cluster.yaml
kubectl --kubeconfig ~/.kube/my-cluster.yaml get nodes -L agentpool,topology.kubernetes.io/zone
```

```
NAME                                STATUS   ROLES    AGE   VERSION        AGENTPOOL   ZONE
aks-nodepool1-12345678-vmss000000   Ready    <none>   40s   v1.36.6+k3s1   nodepool1   0
aks-userpool-12345678-vmss000000    Ready    <none>   40s   v1.36.6+k3s1   userpool    eastus-1
aks-userpool-12345678-vmss000001    Ready    <none>   40s   v1.36.6+k3s1   userpool    eastus-2
```

In real mode the credential operations answer `409 OperationNotAllowed` until the cluster has a kubeconfig (while it
is first `Creating`, or after it `Failed`), rather than a kubeconfig that cannot reach a server.

### 4: Delete the cluster

```bash
curl -s -i -X DELETE \
  "http://localhost:4577/subscriptions/my-sub/resourceGroups/my-rg/providers/Microsoft.ContainerService/managedClusters/my-cluster?api-version=2025-10-01"
# 202 with Azure-AsyncOperation and Location; the cluster shows Deleting until its containers,
# volumes and network are gone, then GET answers 404
```

---

## Lifecycle and long-running operations

| Request | Answer | Operation |
|---|---|---|
| PUT cluster (new) | `201`, `Creating`, `Azure-AsyncOperation`, `Retry-After` | `InProgress` until the API server and the nodes are ready |
| PUT cluster (existing) | `200`, `Updating` (or `Upgrading` for a version change) | the change is applied: pool counts, labels, taints, zones, versions |
| PUT agent pool | `201`/`200`, `Creating`, `Updating`, `Scaling` or `Upgrading`, `Azure-AsyncOperation` | the pool's nodes are created, removed or recreated |
| DELETE cluster or agent pool | `202`, `Azure-AsyncOperation`, `Location`; `204` when absent | containers removed, then the resource |
| POST `stop` / `start` | `202`, `Location` | containers stopped or started; `powerState.code` is `Stopped` or `Running` |

The operation status (`GET .../operations/{id}`) follows the provisioning state of the cluster: `InProgress` while it
is transient, `Succeeded` when it settles, `Failed` with the error message otherwise (for example when the kubeconfig
cannot be read from k3s, or the nodes do not become `Ready` within `ready-timeout-seconds`). The `Location` monitor
(`.../operationresults/{id}`) answers `202` while running and `204` once done, as the
[ManagedClusters_Start example][spec-start] shows. A PUT that arrives while another is applied is applied after it,
and a PUT on a cluster that is `Deleting` fails with `409 OperationNotAllowed`.

**Restore.** With a persistent storage mode (`persistent`, `hybrid`, `wal`), every recorded cluster is reconciled at
startup: surviving server and agent containers are adopted; a lost server is recreated on its data volume (same CA,
same kubeconfigs); a lost volume gives a fresh cluster and a warning that its CA changed. In `memory` mode nothing is
restored. Shutdown still removes the containers unless `keep-running-on-shutdown` is set.

---

## Kubernetes versions

AKS supports the four newest minors; on 2026-11-10 they are 1.34 (end of life November 2026), 1.35, 1.36 and 1.37,
and the portal, CLI and PowerShell create clusters on N-1 ([AKS release calendar][aks-versions]). The emulator runs
one pinned k3s image per minor:

| `kubernetesVersion` | Runs | k3s image |
|---|---|---|
| `1.34` or `1.34.13` | 1.34.13 | `rancher/k3s:v1.34.13-k3s1` |
| `1.35` or `1.35.10` | 1.35.10 | `rancher/k3s:v1.35.10-k3s1` |
| `1.36` or `1.36.6` (default) | 1.36.6 | `rancher/k3s:v1.36.6-k3s1` |
| `1.37` or `1.37.2` | 1.37.2 | `rancher/k3s:v1.37.2-k3s1` |

Patch numbers follow k3s, not AKS's own patch releases. `kubernetesVersion` echoes what was asked (`major.minor` or
the exact patch) and `currentKubernetesVersion` the patch that runs, as the [REST spec][spec-mc] describes ("When
`<major.minor>` is specified, the latest supported GA patch version is chosen automatically"). The rules AKS applies:

- Upgrades go one minor at a time and never down; asking for the same minor again is no upgrade.
- An upgrade (a new `kubernetesVersion` on PUT) recreates the k3s server on the new image with the same data volume
  and host port, so existing kubeconfigs keep working, then recreates the nodes of every pool under their own names.
- An agent pool `orchestratorVersion` change (agent pool PUT) recreates that pool's nodes only. A pool may lag the
  control plane by up to three minors and never lead it ([version skew][aks-versions]).
- Through the managed cluster API, pools name no `orchestratorVersion` or each name one that is unchanged or equal to
  the control plane's; per-pool changes go through the agent pool API.

| Case | HTTP | Code | Message (as AKS words it) |
|---|---|---|---|
| Unsupported cluster version | 400 | `K8sVersionNotSupported` | `Managed cluster <name> is on version <v> which is not supported in this region. Please use [az aks get-versions] command to get the supported version list in this region. For more information, please check https://aka.ms/supported-version-list` ([example][err-k8s]) |
| Unsupported pool version | 400 | `AgentPoolK8sVersionNotSupported` | `Version <v> is not supported in this region. Please use [az aks get-versions] ...` ([example][err-pool]) |
| Skipped or lower minor | 400 | `OperationNotAllowed` | `Upgrading Kubernetes version <from> to <to> is not allowed. Available upgrades: [<versions>]. Please see https://aka.ms/aks-supported-k8s-ver for version details.` ([example][err-upgrade]) |
| Pool ahead of, or more than three minors behind, the control plane | 400 | `NodePoolMcVersionIncompatible` | `Node pool version <p> and control plane version <c> are incompatible. Minor version of node pool version <n> is bigger than control plane version <m>. ...` ([error codes][aks-errors], [example][err-skew]) |
| Mixed or changed pool versions through the managed cluster API | 400 | `NotAllAgentPoolOrchestratorVersionSpecifiedAndUnchanged` | `Using managed cluster api, all Agent pools' OrchestratorVersion must be all specified or all unspecified. ...` ([example][err-notall]) |

The wording of the "more than three minors behind" message is not verified.

`kubernetesVersions`, `upgradeProfiles/default` and `availableAgentPoolVersions` have the shapes of the
[KubernetesVersions_List][spec-examples], ManagedClustersGetUpgradeProfile and AgentPoolsGetAgentPoolAvailableVersions
examples and list only the versions above.

---

## Agent pools as nodes

The k3s server runs with `--disable-agent --egress-selector-mode=cluster`: it has no kubelet, so `kubectl get nodes`
lists only agent pool nodes, as on AKS, and the API server reaches kubelets, pods and services through the agents'
tunnels (pod logs and the metrics API work through them). Each node is a k3s agent container on the cluster's
Docker network `floci-aks-<instanceId>`; the server also joins the emulator's own network when one is configured.

| Node field | Value |
|---|---|
| Name | `aks-<pool>-<8 digits>-vmss<instance>`: the scale set's computer name prefix followed by the instance ID in base 36 over six characters ([labels][aks-labels], [VMSS instance names][vmss-names]) |
| `spec.providerID` | `azure:///subscriptions/<sub>/resourceGroups/<node resource group, lowercased>/providers/Microsoft.Compute/virtualMachineScaleSets/aks-<pool>-<digits>-vmss/virtualMachines/<index>` (the Azure cloud provider lowercases the resource group, [cloud-provider-azure][cpa-lower]) |
| Taints | the pool's `nodeTaints` (`key=value:Effect`, validated) |
| CPU and memory | the container's limits come from the VM size (for example `Standard_D4s_v5`: 4 vCPU, 16 GiB), capped at the Docker host's CPUs |
| `max-pods` | the pool's `maxPods`, defaulting from the network plugin (see [Networking](#networking)) |

Labels, from AKS's [reserved system labels][aks-labels]:

| Label | Value |
|---|---|
| `kubernetes.azure.com/agentpool`, `agentpool` (deprecated, still set) | pool name |
| `kubernetes.azure.com/mode` | `system` or `user` |
| `kubernetes.azure.com/role` | `agent` |
| `kubernetes.azure.com/cluster` | node resource group (`MC_<rg>_<cluster>_<region>` by default) |
| `kubernetes.azure.com/managedby` | `aks` |
| `kubernetes.azure.com/os-sku` | the pool's `osSKU` (`Ubuntu` by default) |
| `kubernetes.azure.com/nodepool-type` | `VirtualMachineScaleSets` |
| `kubernetes.azure.com/sku-cpu`, `kubernetes.azure.com/sku-memory` | vCPUs and GiB of the VM size |
| `node.kubernetes.io/instance-type`, `beta.kubernetes.io/instance-type` | VM size |
| `topology.kubernetes.io/region` | cluster location |
| `topology.kubernetes.io/zone` | `<region>-<zone>` for a zonal pool (nodes spread round-robin over `availabilityZones`), `0` otherwise |
| `kubernetes.azure.com/accelerator`, `accelerator` (deprecated, still set) | `nvidia` on GPU sizes |
| pool `nodeLabels` | as given; keys under `kubernetes.azure.com/` and `kubernetes.io/` are refused with `400 InvalidParameter`, since AKS reserves those prefixes |

Count changes (managed cluster or agent pool PUT) add nodes at the next indexes or remove the highest ones; an agent
pool DELETE removes its nodes; a pool with `count: 0` has no node. Label and taint changes are applied to the running
nodes, and labels or taints a previous request set and this one drops are removed.

VM sizes the emulator knows by heart: DSv2 (DS1 to DS5), Dsv5 (D2s to D32s), NCasT4_v3, NC A100 v4, ND H100 v5 and
NVadsA10 v5 ([Azure VM sizes][vm-sizes]). Any other size takes its vCPU count from its name (the number after the
family letters) and 8 GiB per vCPU for the E family, 2 GiB for F, 4 GiB otherwise.

**Node cap.** `max-nodes-per-cluster` (default 5) bounds the node containers of a cluster. System pools are served
first, then the others in creation order; pools past the cap get fewer nodes, the API still reports their requested
`count`, and a WARN names the cap and the pools short of nodes.

### GPU sizes

Nodes of NC, ND and NV sizes (except the AMD NVv4 and MI300X sizes) carry `kubernetes.azure.com/accelerator=nvidia`
([labels][aks-labels]) and advertise the size's GPU count as `nvidia.com/gpu` capacity and allocatable, so pods with
`resources.limits: {nvidia.com/gpu: 1}` schedule on them (the container still has no GPU). On AKS that capacity
appears once the NVIDIA device plugin runs, which AKS installs on [AKS-managed GPU node pools][aks-managed-gpu] and
which you install yourself otherwise ([Use GPUs on AKS][aks-gpu]); the emulator advertises it directly. AKS adds no
taint to GPU nodes; its examples add `sku=gpu:NoSchedule` through `nodeTaints`, which the emulator applies like any
other taint.

---

## Storage classes

k3s's own `local-path` class is disabled. A k3s auto-deploy manifest creates the classes AKS precreates, and a
`local-path-provisioner` instance registered under each Azure CSI driver name (`disk.csi.azure.com`,
`file.csi.azure.com`) serves them from a Docker volume every node mounts, so claims bind, data survives a node being
recreated, and file shares are `ReadWriteMany` across nodes.

| Class | Provisioner | Parameters | Binding mode |
|---|---|---|---|
| `default` (default class) | `disk.csi.azure.com` | `skuname: StandardSSD_LRS` | `WaitForFirstConsumer` |
| `managed-csi` | `disk.csi.azure.com` | `skuname: StandardSSD_LRS` | `WaitForFirstConsumer` |
| `managed-csi-premium` | `disk.csi.azure.com` | `skuname: Premium_LRS` | `WaitForFirstConsumer` |
| `managed-csi-premium-v2` (Kubernetes 1.35 and later) | `disk.csi.azure.com` | `skuname: PremiumV2_LRS`, `cachingMode: None` | `WaitForFirstConsumer` |
| `managed` | `disk.csi.azure.com` | `cachingmode: ReadOnly`, `kind: Managed`, `storageaccounttype: StandardSSD_LRS` | `WaitForFirstConsumer` |
| `managed-premium` | `disk.csi.azure.com` | `cachingmode: ReadOnly`, `kind: Managed`, `storageaccounttype: Premium_LRS` | `WaitForFirstConsumer` |
| `azurefile-csi` | `file.csi.azure.com` | `skuName: Standard_LRS` | `Immediate` |
| `azurefile-csi-premium` | `file.csi.azure.com` | `skuName: Premium_LRS` | `Immediate` |
| `azurefile` | `file.csi.azure.com` | `skuName: Standard_LRS` | `Immediate` |
| `azurefile-premium` | `file.csi.azure.com` | `skuName: Premium_LRS` | `Immediate` |

All have `reclaimPolicy: Delete`, `allowVolumeExpansion: true` and the labels `addonmanager.kubernetes.io/mode:
EnsureExists` and `kubernetes.io/cluster-service: "true"`. Sources: [storage concepts][aks-storage] (`default` is
`managed-csi`, premium v2 from 1.35, Delete and expandable), [Azure Disks][aks-disk] (`WaitForFirstConsumer`,
`skuName` values, `PremiumV2_LRS` only with caching `None`), [Azure Files][aks-files] (`Immediate`), and a
[`kubectl get sc` listing from an AKS cluster][aks-sc-listing] for the legacy names `managed`, `managed-premium`,
`azurefile`, `azurefile-premium`, the `managed-premium` parameters and the labels. Not verified: the parameters of
`managed`, `azurefile` and `azurefile-premium`. Not emulated: from Kubernetes 1.29, AKS uses ZRS SKUs in the built-in
disk classes of zonal clusters; the emulator keeps LRS. The Blob CSI classes (`azureblob-*`) exist on AKS only when
the Blob driver is enabled and are not created.

---

## Networking

A new cluster's `networkProfile` is completed with AKS's defaults and applied to the k3s server:

| Field | Default | Applied as |
|---|---|---|
| `networkPlugin` / `networkPluginMode` | `azure` / `overlay` (Azure CNI Overlay) | reported; pods still use k3s's flannel |
| `podCidr` (or `podCidrs[0]`) | `10.244.0.0/16` for overlay, kubenet and `none`; none reported for Azure CNI on the node subnet | `--cluster-cidr` (10.244.0.0/16 when none is reported) |
| `serviceCidr` (or `serviceCidrs[0]`) | `10.0.0.0/16` | `--service-cidr`; the `kubernetes` service is `10.0.0.1` |
| `dnsServiceIP` | `10.0.0.10` | `--cluster-dns`; the `kube-dns` service address |
| `networkPolicy` / `networkDataplane` | none | k3s's network policy controller runs only with a policy engine (`azure`, `calico`, `cilium`) or the Cilium dataplane, since AKS enforces NetworkPolicies only then |
| `outboundType` | `loadBalancer` | reported |
| `loadBalancerSku` | `standard` | reported |

GET also reports `podCidrs`, `serviceCidrs` and `ipFamilies: ["IPv4"]`, as the [ManagedClustersGet example][spec-examples]
does. A `dnsServiceIP` outside `serviceCidr` fails with `400 DnsServiceIpOutOfServiceCidr` "The DNS service IP
10.0.0.10 is out of the range defined by service CIDR 10.200.0.0/16." ([troubleshooting][err-dns]); a malformed range
fails with `400 InvalidParameter`. Pools default `maxPods` from the plugin: 250 for Azure CNI Overlay and `none`, 30
for Azure CNI on the node subnet, 110 for kubenet ([az aks create][az-aks], [overlay][aks-overlay]).

Sources: [REST spec][spec-mc] (defaults `10.244.0.0/16`, `10.0.0.0/16`, `10.0.0.10`, `loadBalancer`), [Azure CNI
Overlay][aks-overlay-config] ("If you don't specify `--network-plugin`, AKS defaults to Azure CNI Overlay", default
pod CIDR), [az aks create][az-aks].

Not verified, so kept simple: AKS does not document what a later PUT that changes the ranges answers; the emulator
keeps the ranges the cluster was created with. Dual-stack (IPv6) ranges are not emulated: the first IPv4 range is
used. Clusters recorded before this change keep k3s's own ranges (10.42.0.0/16, 10.43.0.0/16), since their data store
was initialized with them. metrics-server runs, as AKS ships it; traefik does not.

---

## Credentials

Both credential operations return one kubeconfig, base64-encoded, laid out as AKS writes it (an
[`az aks get-credentials` recording][cli-recording] and the [az CLI merge test][cli-merge-test]):

| Operation | `kubeconfigs[0].name` | Cluster and context | User |
|---|---|---|---|
| `listClusterUserCredential` | `clusterUser` | `<cluster>` | `clusterUser_<resourceGroup>_<cluster>` |
| `listClusterAdminCredential` | `clusterAdmin` | `<cluster>` | `clusterAdmin_<resourceGroup>_<cluster>` |

`az aks get-credentials --admin` renames the admin context to `<cluster>-admin` itself when it merges the file, as on
AKS. Both users carry the cluster's k3s client certificate (cluster admin): on AKS without Microsoft Entra
integration the user credential is a certificate with the same rights. The server is the endpoint clients reach the
API server on, not `<dnsPrefix>.hcp.<region>.azmk8s.io:443`.

With `disableLocalAccounts: true`, `listClusterAdminCredential` fails with `400 BadRequest` "Getting static
credential is not allowed because this cluster is set to disable local accounts." ([local accounts][aks-local],
[raw error][err-local]); setting it back to `false` gives the admin credential back. The REST spec says the flag
"must only be used on Managed Clusters that are AAD enabled"; the emulator does not enforce that.

Not emulated (Microsoft Entra integration is out of scope): for Entra-enabled clusters, AKS returns an `exec`
(kubelogin) user kubeconfig and authenticates Entra tokens; the emulator stores and echoes `aadProfile` and keeps
handing out the certificate kubeconfig, and the `format` query parameter is ignored.

---

## Fields stored and echoed

`aadProfile`, `oidcIssuerProfile`, `securityProfile`, `apiServerAccessProfile`, `addonProfiles`,
`autoUpgradeProfile`, `autoScalerProfile`, `linuxProfile`, `windowsProfile`, `workloadAutoScalerProfile`,
`storageProfile`, `ingressProfile`, `azureMonitorProfile`, `identityProfile`, `httpProxyConfig` and the client ID of
`servicePrincipalProfile` (never its secret) are stored and returned as sent, with no runtime effect. Pool
`enableAutoScaling`, `minCount` and `maxCount` are echoed; nothing autoscales.

---

## Real vs Mocked Mode

| | Real mode (`mocked=false`) | Mocked mode (`mocked=true`) |
|---|---|---|
| Docker required | Yes | No |
| `provisioningState` after create | `Creating`, then `Succeeded` once the API server and nodes are ready | `Succeeded` at once |
| Kubeconfig | Real CA and client certificate, real server URL | Synthetic: placeholder token, `insecure-skip-tls-verify: true` |
| `kubectl` connectivity | Yes | No |
| Containers | server `floci-az-aks-{instanceId}`, agents `floci-az-aks-{instanceId}-{node}`, network `floci-aks-{instanceId}` | None |
| Use case | Local development, integration tests | Unit tests, CI without Docker |

---

## Configuration

```yaml
floci-az:
  services:
    aks:
      enabled: true
      mocked: false                   # true = no Docker; false = real k3s (default)
      image-repository: "rancher/k3s" # each Kubernetes minor runs its pinned tag from this repository
      # default-image: ""             # set to pin one image for every cluster, whatever its version
      ready-timeout-seconds: 300
      max-nodes-per-cluster: 5
      api-server-base-port: 6443
      api-server-max-port: 7443
      keep-running-on-shutdown: false
```

| Environment Variable | Default | Description |
|---|---|---|
| `FLOCI_AZ_SERVICES_AKS_ENABLED` | `true` | Enable or disable the AKS service |
| `FLOCI_AZ_SERVICES_AKS_MOCKED` | `false` | `true` = skip Docker, synthetic kubeconfig |
| `FLOCI_AZ_SERVICES_AKS_IMAGE_REPOSITORY` | `rancher/k3s` | Repository of the k3s images; a mirror needs only the tags of the [version table](#kubernetes-versions) |
| `FLOCI_AZ_SERVICES_AKS_DEFAULT_IMAGE` | unset | One image for every cluster; the version then no longer matches what runs |
| `FLOCI_AZ_SERVICES_AKS_READY_TIMEOUT_SECONDS` | `300` | How long the API server and the nodes may take to become ready before the operation fails |
| `FLOCI_AZ_SERVICES_AKS_MAX_NODES_PER_CLUSTER` | `5` | Node containers per cluster across its pools |
| `FLOCI_AZ_SERVICES_AKS_API_SERVER_BASE_PORT` | `6443` | Start of the host port range for k3s API servers |
| `FLOCI_AZ_SERVICES_AKS_API_SERVER_MAX_PORT` | `7443` | End of the host port range for k3s API servers |
| `FLOCI_AZ_SERVICES_AKS_KEEP_RUNNING_ON_SHUTDOWN` | `false` | Leave the containers running when floci-az stops. Deleting a cluster always removes its containers, volumes and network |

Images pulled in real mode: the k3s tag of each version used, `rancher/local-path-provisioner:v0.0.37`,
`rancher/mirrored-library-busybox:1.37.0`, and what k3s pulls for itself (CoreDNS, metrics-server, pause).

---

## Docker Compose

```yaml
services:
  floci-az:
    image: floci/floci-az:latest
    ports:
      - "4577:4577"
      - "6443-6450:6443-6450"   # k3s API server ports (one per cluster)
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock   # required for real k3s mode
    environment:
      FLOCI_AZ_SERVICES_AKS_MOCKED: "false"
```

> **Sidecar ports:** each k3s server binds a host port in the `6443–7443` range through the Docker daemon. Agents
> need no port. Publish the range on the `floci-az` service if your application reaches the API server from outside
> Docker.

---

## Architecture

```
┌────────────────────────────────────────────────────────────────────────┐
│  Your App                                                              │
│                                                                        │
│  ARM REST calls ──────► floci-az :4577 ──► AksHandler ──► reconciler   │
│  (create, scale, upgrade,                (state, LRO)    (containers)  │
│   get credentials)                                                     │
│                                                                        │
│  kubectl / k8s client ─────────► k3s server :6443 (no kubelet)         │
│                                  floci-az-aks-{instanceId}             │
│                                      │  network floci-aks-{instanceId} │
│                                      ├── k3s agent aks-nodepool1-…-vmss000000
│                                      └── k3s agent aks-userpool-…-vmss000000
└────────────────────────────────────────────────────────────────────────┘
```

The management plane (ARM API) goes through floci-az on port 4577. The data plane (kubectl, Kubernetes API) connects
directly to the k3s server on its allocated port: floci-az is not in the data path.

---

## Sources

Checked on 2026-11-10 and 2026-11-11.

- AKS REST spec, `Microsoft.ContainerService/aks/stable/2026-08-01` ([managedClusters.json][spec-mc] at
  `ebc13f6`, [examples][spec-examples])
- Microsoft Learn: [supported Kubernetes versions][aks-versions], [labels][aks-labels], [use GPUs][aks-gpu],
  [AKS-managed GPU node pools][aks-managed-gpu], [storage concepts][aks-storage], [Azure Disks][aks-disk],
  [Azure Files][aks-files], [Azure CNI Overlay concepts][aks-overlay], [configure Azure CNI Overlay][aks-overlay-config],
  [az aks][az-aks], [local accounts][aks-local], [error codes][aks-errors], [DnsServiceIpOutOfServiceCidr][err-dns],
  [VMSS instance names][vmss-names], [VM sizes][vm-sizes]
- Azure clients: [azure-cli `merge_kubernetes_configurations` test][cli-merge-test], [azure-cli recording of
  `listClusterUserCredential`][cli-recording], [cloud-provider-azure `ConvertResourceGroupNameToLower`][cpa-lower]
- Raw AKS errors quoted in issues: [K8sVersionNotSupported][err-k8s], [AgentPoolK8sVersionNotSupported][err-pool],
  [OperationNotAllowed upgrade][err-upgrade], [NodePoolMcVersionIncompatible][err-skew],
  [NotAllAgentPoolOrchestratorVersionSpecifiedAndUnchanged][err-notall], [disable local accounts][err-local];
  [`kubectl get sc` on AKS][aks-sc-listing]

[spec-mc]: https://github.com/Azure/azure-rest-api-specs/blob/ebc13f67950a95e50c6828c27dd979e8608c0329/specification/containerservice/resource-manager/Microsoft.ContainerService/aks/stable/2026-08-01/managedClusters.json
[spec-examples]: https://github.com/Azure/azure-rest-api-specs/tree/ebc13f67950a95e50c6828c27dd979e8608c0329/specification/containerservice/resource-manager/Microsoft.ContainerService/aks/stable/2026-08-01/examples
[spec-start]: https://github.com/Azure/azure-rest-api-specs/blob/ebc13f67950a95e50c6828c27dd979e8608c0329/specification/containerservice/resource-manager/Microsoft.ContainerService/aks/stable/2026-08-01/examples/ManagedClustersStart.json
[aks-versions]: https://learn.microsoft.com/en-us/azure/aks/supported-kubernetes-versions
[aks-labels]: https://learn.microsoft.com/en-us/azure/aks/use-labels
[aks-gpu]: https://learn.microsoft.com/en-us/azure/aks/use-nvidia-gpu
[aks-managed-gpu]: https://learn.microsoft.com/en-us/azure/aks/aks-managed-gpu-nodes
[aks-storage]: https://learn.microsoft.com/en-us/azure/aks/concepts-storage
[aks-disk]: https://learn.microsoft.com/en-us/azure/aks/azure-csi-disk-storage-provision
[aks-files]: https://learn.microsoft.com/en-us/azure/aks/create-volume-azure-files
[aks-overlay]: https://learn.microsoft.com/en-us/azure/aks/concepts-network-azure-cni-overlay
[aks-overlay-config]: https://learn.microsoft.com/en-us/azure/aks/azure-cni-overlay
[az-aks]: https://learn.microsoft.com/en-us/cli/azure/aks?view=azure-cli-latest
[aks-local]: https://learn.microsoft.com/en-us/azure/aks/local-accounts
[aks-errors]: https://learn.microsoft.com/en-us/troubleshoot/azure/azure-kubernetes/error-codes/aks-error-code-page
[err-dns]: https://learn.microsoft.com/en-us/troubleshoot/azure/azure-kubernetes/error-codes/dnsserviceipoutofservicecidr-error
[vmss-names]: https://learn.microsoft.com/en-us/azure/virtual-machine-scale-sets/virtual-machine-scale-sets-instance-ids
[vm-sizes]: https://learn.microsoft.com/en-us/azure/virtual-machines/sizes/overview
[cli-merge-test]: https://github.com/Azure/azure-cli/blob/74f7e490691019bd24dd46ca1e5712c57ea66af0/src/azure-cli/azure/cli/command_modules/acs/tests/latest/test_custom.py
[cli-recording]: https://github.com/Azure/azure-cli/blob/74f7e490691019bd24dd46ca1e5712c57ea66af0/src/azure-cli/azure/cli/command_modules/acs/tests/latest/recordings/test_aks_create_default.yaml
[cpa-lower]: https://github.com/kubernetes-sigs/cloud-provider-azure/blob/baabfb774d73cdeb9d2ad40b0a3f495103d7b712/pkg/provider/azure_vmss.go
[err-k8s]: https://github.com/rancher/aks-operator/issues/678
[err-pool]: https://github.com/pulumi/templates/issues/781
[err-upgrade]: https://github.com/rancher/dashboard/issues/11884
[err-skew]: https://github.com/Azure/terraform-azurerm-aks/issues/465
[err-notall]: https://github.com/hashicorp/terraform-provider-azurerm/issues/20791
[err-local]: https://github.com/Azure/caf-terraform-landingzones/issues/416
[aks-sc-listing]: https://github.com/Azure/AKS/discussions/2784
