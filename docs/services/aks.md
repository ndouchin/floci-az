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
  kubeconfigs laid out as AKS writes them; `disableLocalAccounts` refuses the static ones
- **Microsoft Entra ID**: `aadProfile`, the kubelogin user kubeconfig, Entra tokens authenticated by a token webhook,
  admin groups bound to `cluster-admin`
- **Azure RBAC**: `enableAzureRBAC` authorizes requests against [role assignments](authorization.md) through an
  authorization webhook, with AKS's data actions and Forbidden message
- **Workload Identity**: the OIDC issuer (on by default), service account tokens under its AKS-format URL, the
  upstream mutating webhook add-on, and the token exchange at floci-az's Entra from pods (floci-az TLS required)
- **LoadBalancer Services**: an Azure-shaped address in `status.loadBalancer`, public from `198.51.100.0/24` or
  internal on the cluster network, held by an appliance container that forwards to the nodes
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
[raw error][err-local]); setting it back to `false` gives the admin credential back. On a cluster without Microsoft
Entra integration the user credential fails the same way, since the docs say "any attempt to authenticate with user
or admin credentials will fail" (the exact body of that case is not verified). On an Entra cluster the user
credential is the kubelogin kubeconfig described below.

---

## Microsoft Entra ID and Azure RBAC

### `aadProfile`

| Field | Behavior |
|---|---|
| `managed` | Turns managed Entra integration on; once on it stays on, since AKS can't disable it ([control plane authentication][aks-entra]) |
| `enableAzureRBAC` | Authorizes Kubernetes requests against Azure role assignments (below) |
| `adminGroupObjectIDs` | Bound to `cluster-admin`; answered `null` when none were sent, as the spec example `ManagedClustersCreate_UpdateWithEnableAzureRBAC.json` shows |
| `tenantID` | Defaults to the subscription's tenant |
| `clientAppID`, `serverAppID` | Legacy fields, stored and returned |
| `serverAppSecret` | Stored, never returned (`x-ms-secret`) |

A PUT that omits `aadProfile` keeps it. Not verified: the RP's errors for `enableAzureRBAC` without `managed`, a
`tenantID` other than the subscription's, or a request that turns integration off (the emulator keeps it on).

### User kubeconfig

On an Entra cluster `listClusterUserCredential` returns an exec user, as AKS writes it from Kubernetes 1.24
([kubelogin authentication][aks-kubelogin]):

```yaml
users:
- name: clusterUser_<resourceGroup>_<cluster>
  user:
    exec:
      apiVersion: client.authentication.k8s.io/v1beta1
      args: [get-token, --environment, AzurePublicCloud, --server-id, 6dae42f8-4368-4678-94ff-3960e28e3630,
             --client-id, 80faf920-1908-4b52-b5ef-a8e7bedfc67a, --tenant-id, <tenantID>, --login, devicecode]
      command: kubelogin
```

`6dae42f8-...` is the AKS server application, the audience of every kubelogin mode, and `80faf920-...` the public
client. The az CLI looks for `--login devicecode` and converts the file to `-l azurecli` when kubelogin is installed.
`format=azure` returns the legacy `azure` auth-provider user (`apiserver-id`, `client-id`, `tenant-id`,
`environment`, `config-mode: '1'`), the keys kubelogin's `convert-kubeconfig` reads. The admin credential stays the
certificate kubeconfig. The `installHint`, `env` and `provideClusterInfo` fields are written as commonly observed in
AKS kubeconfigs; not verified against a Tier 1 source.

kubelogin reaches floci-az only over HTTPS (azidentity refuses a plain HTTP authority) and with
`--disable-instance-discovery` (MSAL otherwise asks `login.microsoftonline.com` about the host and gets AADSTS50049).
Either:

- convert to the az CLI mode once the az CLI is signed in to floci-az: `kubelogin convert-kubeconfig -l azurecli`;
- or set `floci-az.services.aks.entra.kubeconfig-floci-authority=true` (with floci-az TLS on), which appends
  `--authority-host https://<floci-az>/ --disable-instance-discovery` to the kubeconfig. `convert-kubeconfig` drops
  those two flags for every mode except `workloadidentity`, so add them again after a conversion.

Tested modes: `spn`, `ropc` and `azurecli` (compat suite `compat-azcli/test/kubelogin.bats`). `devicecode` and
`interactive` need endpoints floci-az does not serve (device code) or a browser.

### Authentication

An Entra cluster's API server runs with `authentication-token-webhook-config-file` pointing at
`POST /_floci/aks/clusters/{instanceId}/token-review`, the role AKS's managed webhook plays
([identity concepts][aks-identity-old]). It does what that webhook (kubeguard/guard, [`auth/providers/azure`][guard-authn])
does:

- the token must be signed by floci-az's Entra, within its lifetime, issued in the cluster's tenant, for the AKS
  server application (Entra issues v1.0 tokens with `upn` and `groups` for it, see [Entra](entra.md#access-token-audience-and-version));
- username = `upn`, else `oid` (service principals and managed identities appear by object id);
- groups = the `groups` claim (object ids), else the directory's direct memberships; `extra.oid` = the object id.

A refused token gets `401 Unauthorized`. Service account tokens and the admin certificate never reach the webhook.

### Admin groups

`adminGroupObjectIDs` become the ClusterRoleBinding `aks-cluster-admin-binding-aad` to `cluster-admin`, written to
the k3s auto-deploy directory and rewritten when the list changes ("AKS automatically generates a ClusterRoleBinding
that binds all of the listed groups to the cluster-admin Kubernetes role", [identity concepts][aks-identity-old]). Its
name on AKS is not documented. With Azure RBAC the admin groups are "irrelevant", so the binding names no subject.

### Azure RBAC

With `enableAzureRBAC: true` the API server runs `authorization-mode=Node,RBAC,Webhook` with its authorization
webhook at `POST /_floci/aks/clusters/{instanceId}/subject-access-review`. Kubernetes RBAC answers first, so a
RoleBinding still grants what it grants and the webhook decides the rest, the union AKS describes
([authorization concepts][aks-authz]). As guard does ([`authz/providers/azure`][guard-authz]):

| Request | Data action checked |
|---|---|
| `get`, `list`, `watch` on `{group}/{resource}` | `Microsoft.ContainerService/managedClusters/{group}/{resource}/read` (no group segment for the core group) |
| `create`, `update`, `patch` | `.../write` |
| `delete`, `deletecollection` | `.../delete` |
| `bind`, `escalate`, `use`, `impersonate` | `.../{verb}/action` |
| `pods/exec` | `.../pods/exec/action`; other subresources check their resource |
| non-resource path | `Microsoft.ContainerService/managedClusters/{path}/{action}` |

The scope is `<clusterId>/namespaces/<namespace>` for a namespaced request, else the cluster id, and assignments at
the cluster, its resource group or its subscription apply below them. The principals are the user's `oid` and its
groups that are object ids. Users whose name starts with `system:` and users without `extra.oid` (service accounts,
certificate users, k3s components) get no opinion, so Kubernetes RBAC decides for them. A denial reads, as kubectl
prints it on AKS ([PIM example][aks-pim]):

```
Error from server (Forbidden): pods is forbidden: User "<upn>" cannot list resource "pods" in API group "" in the
namespace "ns2": User does not have access to the resource in Azure. Update role assignment to allow access.
```

The API server caches webhook answers for `aks.entra.webhook-cache-ttl-seconds` (5 s); AKS documents up to five
minutes for an assignment to take effect. Wildcard requests (`*` verbs, resources or groups) are checked with the `*`
kept in the data action, so only a role whose data actions match it by wildcard grants them; guard expands them from
Azure's operations list. ABAC conditions are not evaluated.

When floci-az is down, Entra users get `401` and Azure RBAC requests are refused; the admin certificate and k3s
components keep working, since the webhooks never see them or give no opinion.

---

## Workload Identity

### OIDC issuer

`oidcIssuerProfile.enabled` is on for new clusters, as AKS does from Kubernetes 1.34, the oldest version the
emulator runs ([OIDC issuer][aks-oidc]); `enabled: false` at creation keeps it off, and once on it stays on.
`issuerURL` is `https://{location}.oic.prod-aks.azure.com/{tenantId}/{uuid}/`, with a uuid generated once per cluster
and kept ([deploy Workload Identity][aks-wi-deploy]). Clusters stored before round 2 have no issuer until a PUT enables
it.

The k3s server signs service account tokens under that URL (`service-account-issuer-=`, which k3s prepends so tokens
issued before stay valid), publishes `{issuerURL}openid/v1/jwks` as its JWKS URL, and follows AKS's token rules: no
extended expiration and at most 24 hours. Enabling the issuer on an existing cluster replaces its server on the same
data volume, as AKS restarts its API server. floci-az reads the cluster's JWKS after every reconcile and serves the
issuer's documents, with the AKS fields (`response_types_supported: ["id_token"]`, `subject_types_supported:
["public"]`, RS256):

| Where | Path |
|---|---|
| The AKS host, when DNS sends `{location}.oic.prod-aks.azure.com` to floci-az | `{issuerURL}.well-known/openid-configuration`, `{issuerURL}openid/v1/jwks` |
| floci-az's own path | `/_floci/aks/oidc/{location}/{tenantId}/{uuid}/.well-known/openid-configuration` and `.../openid/v1/jwks` |

floci-az's Entra resolves the issuer in-process, so federated credentials verify offline.
`rotateServiceAccountSigningKeys` is not emulated.

### Enabling it

`securityProfile.workloadIdentity.enabled: true` needs the issuer; without it the PUT fails with `400`
"Enabling workload identity requires enabling OIDC issuer." (the az CLI checks this client-side; the RP's own error is
not verified). Then:

1. The upstream azure-workload-identity webhook v1.6.3 runs in `kube-system` as `azure-wi-webhook-controller-manager`,
   configured by `azure-wi-webhook-config` (`AZURE_TENANT_ID` = the cluster's tenant), as AKS's add-on does
   ([overview][aks-wi-overview]). Its image is `aks.workload-identity.webhook-image`. Its serving certificate is
   pre-seeded the way cert-controller generates the ones it keeps, so it is Ready seconds after the cluster (about
   two minutes otherwise). Pods labelled `azure.workload.identity/use: "true"` under a service account annotated
   `azure.workload.identity/client-id` get `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_AUTHORITY_HOST`,
   `AZURE_FEDERATED_TOKEN_FILE` (`/var/run/secrets/azure/wi/<hash>/token/azure-identity-token` from v1.6.1) and the
   projected token for `api://AzureADTokenExchange`. Which webhook version AKS runs today is not verified.
2. A selector-less Service `floci-az` in `kube-system` points at floci-az (ports 443 and 80 to floci-az's port, where
   its TLS proxy tells HTTPS from HTTP), and floci-az's CA is published as the ConfigMap `floci-az-ca` (key
   `ca.crt`) in `kube-system`.
3. Pods reach floci-az's Entra over HTTPS, since the Azure SDKs refuse a plain HTTP authority. **floci-az's TLS must
   be on** (`FLOCI_AZ_TLS_ENABLED=true`). Two modes (`aks.workload-identity.entra-mode`):

| Mode | `AZURE_AUTHORITY_HOST` in pods | What floci-az adds | What the workload does |
|---|---|---|---|
| `microsoft-hostnames` (default) | `https://login.microsoftonline.com/`, AKS's value | a `coredns-custom` override (key `floci-az.override`) that answers `login.microsoftonline.com` and the issuer host with the `floci-az` Service; those names in floci-az's certificate; Entra's instance discovery (`common/discovery/instance`) | trusts `floci-az-ca` |
| `authority` | `https://floci-az.kube-system.svc.cluster.local/`, through an `AzureStackCloud` environment file the webhook reads (`AZURE_ENVIRONMENT_FILEPATH`) | the Service name in floci-az's certificate | trusts `floci-az-ca`, and disables instance discovery in code (azidentity has no environment variable for it) |

Turning Workload Identity off removes the add-on.

The exchange itself: the SDK posts the projected token as `client_assertion` with the identity's client id, and
floci-az's Entra checks it against the identity's [federated identity credentials](managed-identity.md) and answers
with an access token for the identity, or Entra's AADSTS codes ([Entra, client assertions](entra.md#client-assertions)).
For the AKS subject, the credential's `subject` is `system:serviceaccount:<namespace>:<service account>`.

### Trusting floci-az's CA

A projected volume cannot read another namespace, so copy `floci-az-ca` into the workload's namespace and mount it,
then point the runtime at it: `SSL_CERT_FILE` (Go, OpenSSL, curl), `REQUESTS_CA_BUNDLE` (Python requests),
`NODE_EXTRA_CA_CERTS` (Node.js), or a Java truststore. floci-az injects nothing into pods itself.

```bash
kubectl get configmap floci-az-ca -n kube-system -o json \
  | jq '.metadata = {name: "floci-az-ca"}' | kubectl apply -n my-app -f -
```

### Networking and limits

- When floci-az runs in a container, the server and the agents join its Docker network, so pods reach it by its
  address there. When it runs on the host, pods reach `host.docker.internal` (the `host-gateway` entry on Linux).
- A `coredns-custom` ConfigMap of your own replaces floci-az's override (AKS documents that ConfigMap for your own
  customizations); keep the `floci-az.override` key in it.
- Python, .NET, Java and JavaScript SDK behavior against the "Microsoft hostnames" mode is not verified; MSAL Go and
  azidentity are, and the end-to-end test exchanges the token with curl as the SDKs do.

---

## LoadBalancer Services

New clusters run k3s without its servicelb (`load-balancer.mode=emulated`, recorded on the cluster), and floci-az
plays AKS's cloud controller for Services of type `LoadBalancer` that name no `loadBalancerClass`
(kubernetes/cloud-provider `wantsLoadBalancer`). Every 3 seconds it adds the
`service.kubernetes.io/load-balancer-cleanup` finalizer, gives the load balancer an address and an appliance
container holding it, writes `status.loadBalancer.ingress: [{ip}]`, and reports the service controller's events
(`EnsuringLoadBalancer`, `EnsuredLoadBalancer`, `SyncLoadBalancerFailed`, `UpdatedLoadBalancer`,
`UnAvailableLoadBalancer`, `DeletingLoadBalancer`, `DeletedLoadBalancer`).

| Service | Address ([public LB][aks-lb], [internal LB][aks-ilb]) |
|---|---|
| default | a public address from `load-balancer.public-cidr` (`198.51.100.0/24`, RFC 5737 TEST-NET-2), unique across every cluster of the emulator |
| `service.beta.kubernetes.io/azure-load-balancer-internal: "true"` | the address the cluster's Docker network gives the appliance |
| `service.beta.kubernetes.io/azure-load-balancer-ipv4`, else the deprecated `spec.loadBalancerIP` | that public address when it is in the range and free; otherwise the Service stays `<pending>` with a `SyncLoadBalancerFailed` event |

The appliance is a container from the cluster's own k3s image that DNATs each Service port to the Ready agent nodes'
NodePorts (only those with a ready endpoint under `externalTrafficPolicy: Local`, never nodes labelled
`node.kubernetes.io/exclude-from-external-load-balancers`), honours `loadBalancerSourceRanges`, and publishes each
TCP port on a free host port, listed in the Service annotation `floci.io/load-balancer-host-ports`
(`TCP/80=127.0.0.1:41234`). Deleting the Service, or changing its type, removes the appliance and then the finalizer;
deleting the cluster removes every appliance, and the public network goes with the last public load balancer.

| From | Public address | Internal address |
|---|---|---|
| A pod of the cluster | yes (kube-proxy answers the status address) | yes |
| A container on the cluster network | through the appliance's cluster-network address | yes |
| A container on `floci-az-aks-lb-public` | yes | no |
| The host | `127.0.0.1:<host port>` from the annotation; on Linux also the address itself | same |

`load-balancer.mode=servicelb` keeps k3s's servicelb (node addresses in the status) and `off` leaves Services
pending. Clusters created before this mode existed keep servicelb when they are restored.

Not emulated: the public IP and load balancer frontend resources in the node resource group (`azure-pip-name`,
`azure-dns-label-name` and the other cloud-provider-azure annotations are accepted and ignored), a fixed internal
address (internal load balancers take the appliance's address, since the cluster network has no configured subnet),
UDP ports on the host, health probes, client IP preservation through the appliance, `allocateLoadBalancerNodePorts:
false`, IPv6. The appliance runs privileged, like the cluster's own containers. AKS's load balancer names
(`kubernetes`, `kubernetes-internal`) are not verified at Tier 1.

---

## Fields stored and echoed

`securityProfile` (except `workloadIdentity`), `apiServerAccessProfile`, `addonProfiles`,
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
      entra:
        kubeconfig-floci-authority: false
        webhook-cache-ttl-seconds: 5
      workload-identity:
        entra-mode: microsoft-hostnames
        webhook-image: "mcr.microsoft.com/oss/v2/azure/workload-identity/webhook:v1.6.3"
      load-balancer:
        mode: emulated
        provisioning-delay-seconds: 0
        public-cidr: "198.51.100.0/24"
        host-ports: dynamic
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
| `FLOCI_AZ_SERVICES_AKS_ENTRA_KUBECONFIG_FLOCI_AUTHORITY` | `false` | Append `--authority-host <floci-az HTTPS URL>/ --disable-instance-discovery` to the Entra user kubeconfig |
| `FLOCI_AZ_SERVICES_AKS_ENTRA_WEBHOOK_CACHE_TTL_SECONDS` | `5` | How long API servers cache the token and authorization webhook answers |
| `FLOCI_AZ_SERVICES_AKS_WORKLOAD_IDENTITY_ENTRA_MODE` | `microsoft-hostnames` | How Workload Identity pods reach floci-az's Entra: `microsoft-hostnames` or `authority` |
| `FLOCI_AZ_SERVICES_AKS_LOAD_BALANCER_MODE` | `emulated` | Who answers LoadBalancer Services in new clusters: `emulated`, `servicelb` or `off` |
| `FLOCI_AZ_SERVICES_AKS_LOAD_BALANCER_PROVISIONING_DELAY_SECONDS` | `0` | Seconds a new load balancer stays pending |
| `FLOCI_AZ_SERVICES_AKS_LOAD_BALANCER_PUBLIC_CIDR` | `198.51.100.0/24` | Range of public load balancer addresses |
| `FLOCI_AZ_SERVICES_AKS_LOAD_BALANCER_HOST_PORTS` | `dynamic` | `dynamic` publishes each TCP port on a free host port, `none` publishes nothing |
| `FLOCI_AZ_SERVICES_AKS_WORKLOAD_IDENTITY_WEBHOOK_IMAGE` | `mcr.microsoft.com/oss/v2/azure/workload-identity/webhook:v1.6.3` | The Workload Identity webhook image |

Images pulled in real mode: the k3s tag of each version used, `rancher/local-path-provisioner:v0.0.37`,
`rancher/mirrored-library-busybox:1.37.0`, what k3s pulls for itself (CoreDNS, metrics-server, pause), and for
Workload Identity clusters the webhook image above.

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
- Entra and Azure RBAC: [control plane authentication][aks-entra], [kubelogin authentication][aks-kubelogin],
  [authorization concepts][aks-authz], [identity concepts (2025 revision)][aks-identity-old], [PIM example][aks-pim];
  kubeguard/guard v0.16.28 [authentication][guard-authn] and [authorization][guard-authz]; kubelogin v0.2.22
- Workload Identity: [OIDC issuer][aks-oidc], [deploy Workload Identity][aks-wi-deploy], [overview][aks-wi-overview];
  azure-workload-identity v1.6.3 [`deploy/azure-wi-webhook.yaml`][wi-yaml]; cert-controller v0.16.0
  [`pkg/rotator/rotator.go`][cert-rotator]; k3s CoreDNS manifest (`/etc/coredns/custom/*.override`)

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
[aks-entra]: https://learn.microsoft.com/en-us/azure/aks/entra-id-control-plane-authentication
[aks-kubelogin]: https://learn.microsoft.com/en-us/azure/aks/kubelogin-authentication
[aks-authz]: https://learn.microsoft.com/en-us/azure/aks/concepts-cluster-authorization
[aks-identity-old]: https://github.com/MicrosoftDocs/azure-aks-docs/blob/611a5efc0de581eac904bca48c90156ba75c8431/articles/aks/concepts-identity.md
[aks-pim]: https://learn.microsoft.com/en-us/azure/aks/privileged-identity-management
[guard-authn]: https://github.com/kubeguard/guard/blob/v0.16.28/auth/providers/azure/azure.go
[guard-authz]: https://github.com/kubeguard/guard/blob/v0.16.28/authz/providers/azure/rbac/checkaccessreqhelper.go
[aks-oidc]: https://learn.microsoft.com/en-us/azure/aks/use-oidc-issuer
[aks-wi-deploy]: https://learn.microsoft.com/en-us/azure/aks/workload-identity-deploy-cluster
[aks-wi-overview]: https://learn.microsoft.com/en-us/azure/aks/workload-identity-overview
[wi-yaml]: https://github.com/Azure/azure-workload-identity/blob/v1.6.3/deploy/azure-wi-webhook.yaml
[cert-rotator]: https://github.com/open-policy-agent/cert-controller/blob/v0.16.0/pkg/rotator/rotator.go
[aks-lb]: https://learn.microsoft.com/en-us/azure/aks/load-balancer-standard
[aks-ilb]: https://learn.microsoft.com/en-us/azure/aks/internal-lb
