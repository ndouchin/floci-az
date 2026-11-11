# Azure Red Hat OpenShift (ARO)

Serves `Microsoft.RedHatOpenShift/openShiftClusters` to ARM clients. Request and response shapes follow the 2025-07-25
spec ([azure-rest-api-specs `OpenShiftClusters/stable/2025-07-25`][spec]); older api-versions are accepted with the
same shapes. The Java SDK `azure-resourcemanager-redhatopenshift` 1.0.0 (api-version 2025-07-25) is exercised by
`AroCompatibilityTest` in the Java compatibility suite.

> **Requires Docker** (in real mode): each cluster is one [MicroShift][microshift] container, run the way
> [MINC][minc] runs it (privileged, systemd inside, CRI-O, kindnet). The Docker daemon must be rootful with cgroup v2.
> Set `FLOCI_AZ_SERVICES_ARO_MOCKED=true` for clusters that are ARM state only, with no container.

A real-mode cluster is a single-node **OKD** (not OCP) MicroShift cluster. It is not a full OpenShift: see
[What is not OpenShift here](#what-is-not-openshift-here) before relying on it.

---

## Endpoints

```
PUT    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.RedHatOpenShift/openShiftClusters/{name}
PATCH  .../openShiftClusters/{name}
GET    .../openShiftClusters/{name}
DELETE .../openShiftClusters/{name}
GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.RedHatOpenShift/openShiftClusters
GET    /subscriptions/{sub}/providers/Microsoft.RedHatOpenShift/openShiftClusters
POST   .../openShiftClusters/{name}/listCredentials
POST   .../openShiftClusters/{name}/listAdminCredentials
GET    /subscriptions/{sub}/providers/Microsoft.RedHatOpenShift/locations/{location}/openShiftVersions
GET    /subscriptions/{sub}/providers/Microsoft.RedHatOpenShift/locations/{location}/openShiftVersions/{version}
GET    /subscriptions/{sub}/providers/Microsoft.RedHatOpenShift/locations/{location}/operationsstatus/{id}
GET    /subscriptions/{sub}/providers/Microsoft.RedHatOpenShift/locations/{location}/operationresults/{id}
```

Not served from the spec: `GET /providers/Microsoft.RedHatOpenShift/operations` and the
`locations/{location}/platformWorkloadIdentityRoleSets` reads.

### Long-running operations

As the ARO resource provider answers them ([ARO-RP frontend][arorp-frontend]):

| Request | Answer |
|---|---|
| PUT, new cluster | `201`, body with `provisioningState: Creating`, `Azure-AsyncOperation: .../locations/{loc}/operationsstatus/{id}` |
| PUT or PATCH, existing cluster | `200`, body with `provisioningState: Updating`, `Azure-AsyncOperation` |
| DELETE | `202` with `Location: .../operationresults/{id}` and `Azure-AsyncOperation: .../operationsstatus/{id}`; `204` when the cluster does not exist |
| `operationsstatus/{id}` | `{"id","name","status","startTime","endTime","error"}`; `status` is the cluster's state while the operation runs (`Creating`, `Updating`, `Deleting`), then `Succeeded` or `Failed` |
| `operationresults/{id}` | `202` while the operation runs, then `204` for a delete, `200` with the cluster otherwise |

The operation status is derived from the cluster: a create is `Succeeded` only once the cluster is ready (see
[Readiness](#readiness)), `Failed` when its container cannot start or it is not ready within
`ready-timeout-seconds` (error code `InternalServerError`, with the reason). Updates change no runtime state and
complete on the next poll. Every answer carries `Retry-After: 1`. Operations are kept in memory, newest 1000.

In mocked mode a create and an update are `Succeeded` at once.

### Errors

The ARO frontend's codes and messages, with `target` where ARO sets one:

| Case | Answer |
|---|---|
| Unknown cluster | `404 ResourceNotFound` "The Resource 'openshiftclusters/{name}' under resource group '{rg}' was not found." (ARO lowercases the path) |
| Version with no image | `400 InvalidParameter`, target `properties.clusterProfile.version`, "The requested OpenShift version '{v}' is invalid." ([ARO-RP `validateInstallVersion`][arorp-validate]) |
| Bad domain, CIDR, client id, visibility, profile counts | `400 InvalidParameter` (or `InvalidCIDRRange`, `InvalidNetworkAddress`) with ARO's static-validation message |
| Change to an immutable property | `400 PropertyChangeNotAllowed` "Changing property 'properties.{path}' is not allowed." |
| PUT, PATCH, DELETE or credentials while `Creating`, `Updating` or `Deleting` | `400 RequestNotAllowed` "Request is not allowed in provisioningState '{state}'." |
| PUT or PATCH after a failed create | `400 RequestNotAllowed` "Request is not allowed on cluster whose creation failed. Delete the cluster." |
| Unknown operation | `404 NotFound` "The entity was not found." |
| Unknown `openShiftVersions/{v}` | `400 ResourceNotFound` "The Resource openShiftVersion with version '{v}' was not found in the namespace 'microsoft.redhatopenshift' for api version '{api}'." |

Validation follows ARO-RP `pkg/api/v20250725/openshiftcluster_validatestatic.go` for the fields a request sends;
fields it omits get the defaults below instead of ARO's "The field '...' is required." Mutable after creation, as in
`openshiftcluster_updatepolicy.go`: `tags`, `servicePrincipalProfile.clientId`/`clientSecret`,
`platformWorkloadIdentityProfile`, `networkProfile.loadBalancerProfile.managedOutboundIps`, `identity`.

---

## Quickstart

```bash
ARM=http://localhost:4577/subscriptions/00000000-0000-0000-0000-000000000001
curl -s -X PUT "$ARM/resourceGroups/rg/providers/Microsoft.RedHatOpenShift/openShiftClusters/demo?api-version=2025-07-25" \
  -H "Content-Type: application/json" \
  -d '{"location":"eastus","properties":{"clusterProfile":{"domain":"demo","version":"4.19.0"}}}' -D - | grep -i azure-asyncoperation

# poll the operation until "status": "Succeeded" (about 2 to 3 minutes on a laptop)
curl -s "<Azure-AsyncOperation URL>"

curl -s -X POST "$ARM/resourceGroups/rg/providers/Microsoft.RedHatOpenShift/openShiftClusters/demo/listAdminCredentials?api-version=2025-07-25" \
  | python3 -c "import sys,json,base64; print(base64.b64decode(json.load(sys.stdin)['kubeconfig']).decode())" > demo.kubeconfig
oc --kubeconfig demo.kubeconfig get nodes,clusterversion,storageclass
```

The `az aro` commands and Terraform's `azurerm_redhat_openshift_cluster` have not been tested against floci-az.
`az aro create` also reads the virtual network and assigns roles before it sends the PUT, so it depends on more than
this service.

### Reaching Routes

The router's ports 80 and 443 are published on host ports from the same range as the API server. A Route gets the
host `<route>-<namespace>.apps.<domain>`, which does not resolve; send the request to the router with the host:

```bash
docker port floci-az-aro-<id> 80            # the host port of the router's HTTP port
curl -H "Host: web-demo.apps.demo.eastus.aroapp.io" http://localhost:<port>/
```

---

## What a cluster looks like

### ARM resource

| Field | Value | Source |
|---|---|---|
| `type` | `Microsoft.RedHatOpenShift/openShiftClusters` | spec, ARO-RP `register.go` |
| `clusterProfile.domain` | as sent; when absent, a letter and seven lowercase alphanumerics, as `az aro create` picks it | [azure-cli `aro/custom.py`][azcli] |
| full domain | `<domain>.<location>.aroapp.io` for a domain without a dot, the domain itself otherwise | ARO-RP `pkg/util/dns` `ManagedDomain` |
| `clusterProfile.version` | as requested; default the newest advertised | ARO-RP `setDefaultVersionIfEmpty` |
| `clusterProfile.resourceGroupId` | as sent, lowercased; default `/subscriptions/{sub}/resourceGroups/aro-{domain}`. The ARO API requires the field; `az aro create` sends `aro-<random id>`, the same id it uses as the domain when none is given | ARO-RP `putorpatch` (lowercases), [azure-cli `aro/custom.py`][azcli] |
| `clusterProfile.fipsValidatedModules` | default `Disabled` | ARO-RP `api.SetDefaults` |
| `consoleProfile.url` | `https://console-openshift-console.apps.<full domain>/` | [Connect to an ARO cluster][connect] |
| `apiserverProfile.url` | real mode: the reachable endpoint (`https://localhost:<port>/`); mocked: `https://api.<full domain>:6443/` | spec example (shape); emulator choice (reachability) |
| `apiserverProfile.ip`, `ingressProfiles[].ip` | the address the ports are published on (`127.0.0.1` locally) | emulator choice |
| `networkProfile.podCidr` / `serviceCidr` | default `10.128.0.0/14` / `172.30.0.0/16` | [`az aro create` reference][azaro] |
| `networkProfile.outboundType`, `preconfiguredNSG`, `loadBalancerProfile` | default `Loadbalancer`, `Disabled`, `{"managedOutboundIps":{"count":1}}` | ARO-RP `api.SetDefaults` |
| `masterProfile.vmSize` | default `Standard_D8s_v5` | [`az aro create` reference][azaro] |
| `workerProfiles` | default one profile `worker`, `Standard_D4s_v5`, 128 GB, count 3 | [`az aro create` reference][azaro] |
| `workerProfilesStatus` | on GET and list only, one entry per zone `<infraID>-worker-<location><1..3>`, the count spread across them | ARO-RP enricher (fields); MachineSet naming not verified on a live cluster |
| `servicePrincipalProfile` | `clientId` only, never the secret; the pull secret is never returned either | ARO-RP `putorpatch` |
| `listCredentials` | `kubeadminUsername: kubeadmin`, a password stored with the cluster, 23 characters `xxxxx-xxxxx-xxxxx-xxxxx` from letters and digits without `l`, `O`, `0`, `1` | [openshift/installer `pkg/asset/password`][installer-password] |
| `listAdminCredentials` | `{"kubeconfig": "<base64>"}`, MicroShift's admin kubeconfig with the server set to the reachable endpoint | spec example |
| `openShiftVersions` | one entry per configured minor, `name` and `properties.version` the `x.y.z` its image tag starts with, `type: Microsoft.RedHatOpenShift/OpenShiftVersion`, no `id` | ARO-RP `openShiftVersionConverter` |

`platformWorkloadIdentityProfile`, `identity`, subnet ids, disk encryption sets and fields of older api-versions are
stored and echoed; nothing acts on them.

### Inside the cluster

| What | Value | Source |
|---|---|---|
| Node | one node `<infraID>-master-0`, roles `control-plane,master,worker`; the container's hostname is the node name too | ARO-RP `generateInfraID` (`<name>-<5 random>`, 27 characters at most) |
| Node labels | `topology.kubernetes.io/region=<location>`, `topology.kubernetes.io/zone=<location>-1`, `node.kubernetes.io/instance-type=<master vmSize>` | [AKS availability zones][zones] (Azure zone label format) |
| `spec.providerID` | `azure:///subscriptions/<sub>/resourceGroups/<cluster resource group>/providers/Microsoft.Compute/virtualMachines/<node>` | Azure cloud provider format |
| Pod and service CIDRs | the cluster's `podCidr` and `serviceCidr` (MicroShift `network.clusterNetwork`/`serviceNetwork`); DNS at `.10` of the service CIDR | MicroShift config |
| Routes | `*.apps.<full domain>` (MicroShift `dns.baseDomain`) | MicroShift config |
| API certificate | also valid for `api.<full domain>` and the container name | MicroShift `apiServer.subjectAltNames` |
| `managed-csi` (default) | `disk.csi.azure.com`, `skuname: Premium_LRS`, `WaitForFirstConsumer`, expansion on, `Delete` | [openshift/csi-operator release-4.19][csi-disk]; the [ARO FAQ][aro-faq]: "Azure Disk (Premium_LRS) is configured as the default storage class" |
| `azurefile-csi` | `file.csi.azure.com`, `skuName: Standard_LRS`, `matchTags`, `tags`, `Immediate`, expansion on, `Delete`; RWX. Not created for a cluster with `platformWorkloadIdentityProfile` (managed identities) | [openshift/csi-operator release-4.19][csi-file]; [Configure an Azure File StorageClass][aro-azurefile]: "The default Azure File StorageClass is disabled in clusters with managed identity enabled by default" |
| `ClusterVersion/version` | `spec.clusterID` a random UUID; `status.desired.version` and a `Completed` history entry with the OKD version the image runs (`4.19.0-okd-scos.17`), image `quay.io/okd/scos-release:<tag>` | [openshift/api release-4.19][openshift-api] CRD |
| `Infrastructure/cluster` | platform `Azure`, `apiServerURL https://api.<full domain>:6443`, `apiServerInternalURI https://api-int.<full domain>:6443`, `infrastructureName <infraID>`, `platformStatus.azure` with `cloudName AzurePublicCloud`, the cluster and network resource groups, topology `HighlyAvailable` as ARO reports it | openshift/api CRD |
| `Ingress/cluster` | `spec.domain apps.<full domain>` | openshift/api CRD |
| SCCs | MicroShift's: `anyuid`, `hostaccess`, `hostmount-anyuid`, `hostnetwork`, `hostnetwork-v2`, `nonroot`, `nonroot-v2`, `privileged`, `restricted`, `restricted-v2`; a new namespace gets `openshift.io/sa.scc.uid-range` and the Pod Security labels `restricted` | MicroShift |

Pod admission works as on OpenShift. A pod created by a controller (a Deployment, a Job) is admitted with the
service account's SCCs, which is `restricted-v2` with a UID from the namespace range. A bare pod created directly by a
cluster administrator, such as the admin kubeconfig's `system:admin`, is matched first against `anyuid`, which has
priority 10 for administrators, gets no restricted defaults, and is then refused by Pod Security `restricted` unless it
sets a restricted security context itself: "When you create a workload resource, such as deployment, only the service
account is used to find the SCCs and admit the pods" ([openshift-docs, SCCs][ocp-scc]). Both cases were measured on an
emulated cluster.

The two storage classes are served by `rancher/local-path-provisioner` instances registered under the Azure CSI
driver names, in `openshift-cluster-csi-drivers` (deployments `azure-disk-csi-driver-controller` and, when the cluster
has `azurefile-csi`, `azure-file-csi-driver-controller`, labelled `app.kubernetes.io/managed-by: floci-az`). Volumes are directories in
the cluster's data volume; `azurefile-csi` drops the CIFS `mountOptions` (`mfsymlinks`, `cache=strict`, ...) that a
host-path volume cannot take, and resizing a claim does nothing. The cluster pulls
`docker.io/rancher/local-path-provisioner` and `docker.io/library/busybox` the first time.

The `config.openshift.io` CRDs are the Default feature-set manifests of openshift/api, applied with their objects at
every start through MicroShift's `manifests.d`; the status is written once the cluster is up. No operator reconciles
them.

### Readiness

A cluster is `Succeeded` when MicroShift's systemd unit is active, the API answers `/readyz`, every pod in
`openshift-dns`, `openshift-ingress`, `openshift-service-ca` and `openshift-cluster-csi-drivers` runs, and the node,
`ClusterVersion`, `Infrastructure` and `Ingress` have been updated. Measured: on a 6-CPU arm64 VM the API answered
after 52 s and every pod ran after 140 to 170 s; on an amd64 Docker-in-Docker runner the cluster was `Succeeded` after
126 to 159 s. The first create also pulls the image (0.85 GB arm64, 1.1 GB amd64). The container uses about 1.3 GiB of
memory.

---

## Configuration

| Key (`floci-az.services.aro.*`) | Default | |
|---|---|---|
| `enabled` | `true` | |
| `mocked` | `false` | `true`: no container, ARM state and a synthetic kubeconfig pointing at `https://api.<full domain>:6443` |
| `image-repository` | `quay.io/minc-org/minc` | |
| `versions` | `4.19=4.19.0-okd-scos.17` | comma-separated `minor=tag`; the tag gets `-arm64` or `-amd64` after the Docker daemon's architecture. Only these minors are advertised; any `x.y.z` of one is accepted and echoed |
| `base-port` / `max-port` | `7600` / `7699` | host ports for the API server and the router's 80 and 443 (three per cluster) |
| `ready-timeout-seconds` | `900` | a cluster still not ready after this ends `Failed` |
| `storage-provisioner-image` | `docker.io/rancher/local-path-provisioner:v0.0.37` | |
| `storage-helper-image` | `docker.io/library/busybox:1.37` | |
| `keep-running-on-shutdown` | `false` | |

### Persistence and restart

With a persistent storage mode (`persistent`, `hybrid`, `wal`), clusters are restored at startup: a surviving container
is adopted (the cluster stays `Succeeded`, same kubeconfig); otherwise a new container starts on the surviving data
volume (same CA, new kubeconfig after it is ready again); otherwise a new cluster starts and the log says its CA
changed. At shutdown the container and its volumes are removed unless `keep-running-on-shutdown` is set, as for AKS,
so without that flag a restart recreates the cluster. In memory mode nothing is restored. `DELETE` and
`POST /_admin/reset` remove the container and both volumes.

---

## What is not OpenShift here

- **One node.** Masters and workers are the same MicroShift node; `masterProfile` and `workerProfiles` only shape the
  ARM resource, the node's instance-type label and `workerProfilesStatus`. There are no MachineSets, no machine API
  and no scaling.
- **OKD, not OCP.** The image is an OKD (CentOS Stream CoreOS) build of MicroShift 4.19; `ClusterVersion` reports the
  OKD version, not the `4.19.x` the ARM resource echoes. There are no cluster upgrades.
- **No OAuth server.** `listCredentials` returns a kubeadmin password, but `oc login -u kubeadmin` fails: MicroShift has
  no OAuth server and no identity providers. Use the admin kubeconfig.
- **No console.** `consoleProfile.url` has ARO's shape; nothing serves it.
- **Few OpenShift APIs.** MicroShift serves `route.openshift.io` and `security.openshift.io` (Routes and SCCs). The
  `ClusterVersion`, `Infrastructure` and `Ingress` objects are static. There is no `project.openshift.io`, no
  `config.openshift.io` beyond those three, no `operator.openshift.io`, no image registry, builds, monitoring or
  cluster operators. OLM runs, with no catalog.
- **Not the ARO network.** kindnet instead of OVN-Kubernetes; private API or ingress visibility, user-defined routing,
  NSGs and outbound IPs change nothing; `networkProfile.loadBalancerProfile.effectiveOutboundIps` is not returned.
  kube-proxy keeps MicroShift's fixed `clusterCIDR: 10.42.0.0/16` whatever the cluster's `podCidr`.
- **No Azure integration in the cluster.** No Azure cloud provider, no real Azure Disk or Azure Files, no workload
  identity; the pull secret is ignored.

## Not verified

- The MachineSet names behind `workerProfilesStatus` follow the installer's `<infraID>-worker-<region><zone>` pattern
  and have not been compared with a live ARO cluster.
- Whether ARO returns `Retry-After` on every LRO answer: the spec example shows one on PUT, ARO-RP's frontend sets
  none; floci-az sends `Retry-After: 1` so clients poll quickly.
- `ClusterVersion` conditions, `versionHash` and the `Infrastructure` topology values are modelled on OCP, not read
  from an ARO cluster.

[spec]: https://github.com/Azure/azure-rest-api-specs/tree/main/specification/redhatopenshift/resource-manager/Microsoft.RedHatOpenShift/OpenShiftClusters/stable/2025-07-25
[arorp-frontend]: https://github.com/Azure/ARO-RP/tree/master/pkg/frontend
[microshift]: https://github.com/openshift/microshift
[minc]: https://github.com/minc-org/minc
[azcli]: https://github.com/Azure/azure-cli/blob/dev/src/azure-cli/azure/cli/command_modules/aro/custom.py
[azaro]: https://learn.microsoft.com/en-us/cli/azure/aro?view=azure-cli-latest#az-aro-create
[connect]: https://learn.microsoft.com/en-us/azure/openshift/connect-cluster
[zones]: https://learn.microsoft.com/en-us/azure/aks/reliability-availability-zones-configure
[installer-password]: https://github.com/openshift/installer/blob/main/pkg/asset/password/password.go
[csi-disk]: https://github.com/openshift/csi-operator/blob/release-4.19/assets/overlays/azure-disk/generated/standalone/storageclass.yaml
[csi-file]: https://github.com/openshift/csi-operator/blob/release-4.19/assets/overlays/azure-file/generated/standalone/storageclass.yaml
[openshift-api]: https://github.com/openshift/api/tree/release-4.19/config/v1/zz_generated.crd-manifests
[arorp-validate]: https://github.com/Azure/ARO-RP/blob/master/pkg/frontend/validate.go
[aro-faq]: https://learn.microsoft.com/en-us/azure/openshift/openshift-faq
[aro-azurefile]: https://learn.microsoft.com/en-us/azure/openshift/howto-configure-azure-file-storageclass
[ocp-scc]: https://github.com/openshift/openshift-docs/blob/enterprise-4.19/modules/security-context-constraints-about.adoc
