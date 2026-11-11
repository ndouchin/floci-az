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

# or log in as kubeadmin, as on ARO (see "oc login and the OAuth server")
CLUSTER="$ARM/resourceGroups/rg/providers/Microsoft.RedHatOpenShift/openShiftClusters/demo"
API=$(curl -s "$CLUSTER?api-version=2025-07-25" | python3 -c "import sys,json; print(json.load(sys.stdin)['properties']['apiserverProfile']['url'])")
PW=$(curl -s -X POST "$CLUSTER/listCredentials?api-version=2025-07-25" | python3 -c "import sys,json; print(json.load(sys.stdin)['kubeadminPassword'])")
oc login "$API" -u kubeadmin -p "$PW" --insecure-skip-tls-verify   # or --certificate-authority=<CA of demo.kubeconfig>
oc whoami                                                           # kube:admin
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
| `consoleProfile.url` | `https://console-openshift-console.apps.<full domain>/`; in real mode with the router's published HTTPS port, `https://console-openshift-console.apps.<full domain>:<port>/` (see [the web console](#the-web-console)) | [Connect to an ARO cluster][connect] (shape); emulator choice (reachability) |
| `apiserverProfile.url` | real mode: the cluster's API proxy in floci-az (`https://localhost:<proxy port>/`, see [`oc login`](#oc-login-and-the-oauth-server)); mocked: `https://api.<full domain>:6443/` | spec example (shape); emulator choice (reachability) |
| `apiserverProfile.ip`, `ingressProfiles[].ip` | the address the ports are published on (`127.0.0.1` locally) | emulator choice |
| `networkProfile.podCidr` / `serviceCidr` | default `10.128.0.0/14` / `172.30.0.0/16` | [`az aro create` reference][azaro] |
| `networkProfile.outboundType`, `preconfiguredNSG`, `loadBalancerProfile` | default `Loadbalancer`, `Disabled`, `{"managedOutboundIps":{"count":1}}` | ARO-RP `api.SetDefaults` |
| `masterProfile.vmSize` | default `Standard_D8s_v5` | [`az aro create` reference][azaro] |
| `workerProfiles` | default one profile `worker`, `Standard_D4s_v5`, 128 GB, count 3 | [`az aro create` reference][azaro] |
| `workerProfilesStatus` | on GET and list only, one entry per zone `<infraID>-worker-<location><1..3>`, the count spread across them | ARO-RP enricher (fields); MachineSet naming not verified on a live cluster |
| `servicePrincipalProfile` | `clientId` only, never the secret; the pull secret is never returned either | ARO-RP `putorpatch` |
| `listCredentials` | `kubeadminUsername: kubeadmin`, a password stored with the cluster, 23 characters `xxxxx-xxxxx-xxxxx-xxxxx` from letters and digits without `l`, `O`, `0`, `1` | [openshift/installer `pkg/asset/password`][installer-password] |
| `listAdminCredentials` | `{"kubeconfig": "<base64>"}`, MicroShift's admin kubeconfig (client certificate `system:admin`) with the server set to `apiserverProfile.url` | spec example |
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
| Secret `kube-system/kubeadmin` | key `kubeadmin`, the bcrypt (`$2a$`, cost 10) of the `listCredentials` password, created once when the cluster first becomes ready | [openshift-docs `authentication-kubeadmin.adoc`][ocp-kubeadmin]; installer's Go `bcrypt.DefaultCost` |
| ClusterRoleBinding `cluster-admins` | `cluster-admin` for Group `system:cluster-admins` and User `system:admin`, reapplied at every start | [openshift-docs `rbac-viewing-cluster-roles.adoc`][ocp-rbac] |
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
`openshift-dns`, `openshift-ingress`, `openshift-service-ca` and `openshift-cluster-csi-drivers` runs, the node,
`ClusterVersion`, `Infrastructure` and `Ingress` have been updated, `kube-system/kubeadmin` and `cluster-admins` exist,
and the cluster's API proxy serves the OAuth metadata, so `oc login` works as soon as the cluster is `Succeeded`. Measured: on a 6-CPU arm64 VM the API answered
after 52 s and every pod ran after 140 to 170 s; on an amd64 Docker-in-Docker runner the cluster was `Succeeded` after
126 to 159 s. The first create also pulls the image (0.85 GB arm64, 1.1 GB amd64). The container uses about 1.3 GiB of
memory.

---

## `oc login` and the OAuth server

On ARO, `oc login <apiserverProfile.url> -u kubeadmin -p <kubeadminPassword>` logs in through the cluster's OAuth
server ([Connect to an ARO cluster][connect]). MicroShift has no OAuth server: its API server answers 404 at
`/.well-known/oauth-authorization-server` and takes no token webhook or extra flags (MicroShift `pkg/config`,
`assets/controllers/kube-apiserver/defaultconfig.yaml`: `oauthMetadataFile: ""`). So floci-az runs a TLS **API
proxy** per cluster, in its own process, on a host port from `proxy-base-port`-`proxy-max-port` (7700-7799).
`apiserverProfile.url` and the admin kubeconfig point at it. It serves the OpenShift OAuth server and forwards every
other request to MicroShift's API server.

| Request | Answer | Source |
|---|---|---|
| `GET /.well-known/oauth-authorization-server` | the authentication operator's metadata; `issuer` is `https://<the Host the client used>` | [cluster-authentication-operator `metadata_controller.go`][cao-metadata] |
| `/oauth/authorize` | code flow with PKCE (`plain`, `S256`). `openshift-challenging-client` (`oc login -u`): `401` with `WWW-Authenticate: Basic realm="openshift"` when the request has `X-CSRF-Token`, else `401` with only a `Warning`; the browser clients get a login page at `/login/kube:admin` and a session cookie `ssn` (5 minutes). Codes are single use and expire after 300 s | [oauth-server][oauth-server] `osin/authorize.go`, `passwordchallenger` |
| `POST /oauth/token` | `{"access_token":"sha256~<43>","expires_in":86400,"scope":"user:full","token_type":"Bearer"}`, no refresh token; `scope` is `user:full` when the client asked for none. Errors as osin JSON with status 400 (`invalid_grant`, `invalid_request`, `unauthorized_client`, `unsupported_grant_type`) | `osin/access.go`, `pkg/oauth/handlers/grant.go`, `pkg/osinserver/defaults.go` |
| `/oauth/token/request`, `/oauth/token/display` | the "Display Token" pages, with the `oc login --token=... --server=...` command | `openshift-browser-client` |
| `/logout` | ends the login session | |
| `DELETE /apis/oauth.openshift.io/v1/oauthaccesstokens/<name>` | revokes the token whose object name is `sha256~<base64url(SHA-256(token))>` (`oc logout`), for its owner or a cluster administrator | `pkg/osinserver/registrystorage/storage.go` |
| `GET /apis/user.openshift.io/v1/users/~` | the virtual User of a kubeadmin token or client certificate (older `oc` versions ask for it) | [oauth-apiserver `user/.../etcd.go`][oauth-apiserver-user] |
| `GET /console` | `302` to the console when it runs | OpenShift kube-apiserver `withConsoleRedirect` |
| anything else | forwarded to MicroShift, streamed both ways; `Upgrade` requests (exec, attach, cp, port-forward, over WebSocket or SPDY) become a byte tunnel | |

What the API server sees, through MicroShift's request-header authentication (the proxy presents MicroShift's
aggregator client certificate and sets `X-Remote-User`, `X-Remote-Group` and `X-Remote-Extra-*`; incoming `X-Remote-*`
headers are always dropped, `Impersonate-*` headers pass):

| Credential | User | Measured with |
|---|---|---|
| token from `oc login -u kubeadmin` | `kube:admin`, groups `system:cluster-admins`, `system:authenticated`, extra `scopes.authorization.openshift.io: [user:full]` | `oc whoami`, SelfSubjectReview |
| admin kubeconfig (client certificate from MicroShift's client CA) | `system:admin`, `system:masters` | SelfSubjectReview |
| ServiceAccount token, unknown token, no credentials | forwarded as sent, so MicroShift decides | SelfSubjectReview |

`kube:admin` follows library-go's bootstrap authenticator, which oauth-server and oauth-apiserver use: the basic-auth
name is `kubeadmin` (or `kube:admin`), the password is checked against `kube-system/kubeadmin`, passwords under 23
characters are refused, and the secret is ignored when it is being deleted, absent, or created more than one hour after
`kube-system`. Deleting it is how OpenShift removes kubeadmin ([`authentication-remove-kubeadmin.adoc`][ocp-remove-kubeadmin]):
the next login answers 401, and tokens already issued stop working within 10 seconds, since a token is tied to the
secret's UID and resource version. floci-az creates the secret once; a recreate on the same volume does not bring a
deleted kubeadmin back.

**Certificates.** The proxy serves MicroShift's own serving certificates: the localhost one to `localhost` and clients
that send no SNI, the external one (which names `api.<full domain>`, the container name and floci-az's host name when
it is not `localhost`) otherwise. `oc login --certificate-authority=<file>` with the CA of the admin kubeconfig verifies,
as does `--insecure-skip-tls-verify`. HTTP/1.1 only. The files are read when the proxy starts; MicroShift's serving
certificates are valid for one year, and how MicroShift renews them was not checked.

**Where the API URL points**, per Docker mode (`P` the proxy port, `Q` MicroShift's published 6443):

| floci-az runs | `apiserverProfile.url` | floci-az reaches MicroShift at |
|---|---|---|
| on the host, local Docker daemon | `https://localhost:P` | `https://localhost:Q` |
| in Docker (`hostname` or `base-url` names it) | `https://<floci-az host>:P`; publish `7700-7799` on the floci-az container as `P:P` so host clients can use `https://localhost:P` | `https://<cluster container>:6443` on the shared network |
| `docker.endpoint-mode=published`, daemon on loopback | `https://localhost:P` | `https://localhost:Q` |
| `docker.endpoint-mode=published`, remote daemon | `https://<floci-az host>:P` | `https://<daemon host>:Q` |

Only the first and third rows run in tests; the others follow the same rules and are covered by unit tests only.

**Deviations** from a real cluster:

- The OAuth issuer is the API URL the client used, not `https://oauth-openshift.apps.<domain>`, so `oc login` needs
  no DNS.
- Tokens work through the API URL only; MicroShift's own port does not know them.
- kubeadmin is the only identity provider. There are no OAuth API objects (`oc get oauthaccesstokens`, `users`,
  `identities`, `groups`), no `OAuth/cluster`, no token inactivity timeout; login sessions and codes are kept in memory.
- `oc login` prints `Using "default" namespace` instead of the project list: there is no `project.openshift.io`.
- `users/~` is answered for kubeadmin tokens and client certificates only; for other credentials it reaches
  MicroShift, which answers 404.
- The login page is a minimal HTML form, not OpenShift's.

---

## The web console

On ARO, users "Launch the console URL in a browser and sign in using the kubeadmin credentials"
([Connect to an ARO cluster][connect]). A real-mode cluster runs OKD 4.19's own console, deployed as the
console-operator deploys it ([openshift/console-operator `release-4.19`][console-operator] `manifests/` and
`bindata/assets/`): namespaces `openshift-console`, `openshift-console-user-settings` and `openshift-config-managed`,
ServiceAccount, ClusterRole and bindings `console` and `console-auth-delegator`, Service `console` (443 to 8443, serving
certificate from service-ca), Route `console` (reencrypt, `console-openshift-console.apps.<full domain>`, 5 minute
timeout), Deployment `console` (probes `GET /health` on 8443, requests 10m CPU and 100Mi), the OAuth client `console`
with its secret, and ConfigMap `openshift-config-managed/console-public`, so `oc whoami --show-console` prints the
console URL and `GET /console` on the API URL redirects to it. The branding is `azure`, which ARO sets
([ARO-RP `consolebranding.go`][aro-branding]).

To use it from a browser, both `console-openshift-console.apps.<full domain>` and `api.<full domain>` must resolve to
the machine floci-az and the cluster's ports are on. ARO's default `<domain>.<location>.aroapp.io` does not resolve, so
create the cluster with a domain that does: a `clusterProfile.domain` with a dot is used as is, as on ARO, and
`*.127.0.0.1.nip.io` resolves to `127.0.0.1`.

```bash
curl -s -X PUT "$ARM/resourceGroups/rg/providers/Microsoft.RedHatOpenShift/openShiftClusters/demo?api-version=2025-07-25" \
  -H "Content-Type: application/json" \
  -d '{"location":"eastus","properties":{"clusterProfile":{"domain":"demo.127.0.0.1.nip.io","version":"4.19.0"}}}'
# once Succeeded: open consoleProfile.url, e.g. https://console-openshift-console.apps.demo.127.0.0.1.nip.io:7602/,
# accept the router's and the API's certificates, log in as kubeadmin with the listCredentials password
```

The login page is the OAuth server's on `https://api.<full domain>:<proxy port>`. Measured on the amd64 runner: the
console was available about 10 seconds after the cluster was `Succeeded` (the image, 149 MB compressed, was pulled
from quay.io by the cluster), and the login as `kube:admin`, API calls through the console and logout (which revokes
the token) all worked. The design spike measured a 19 MB working set for the console after one login.

How it differs from a real cluster:

- **The bridge runs off-cluster against floci-az's API proxy**, with `--k8s-mode=off-cluster` and
  `--k8s-mode-off-cluster-service-account-bearer-token-file`, which upstream labels "DEV ONLY": in-cluster mode would
  send the user's `sha256~` token to MicroShift's API server, which cannot validate it. The pod resolves
  `api.<full domain>` to the address the cluster reaches floci-az at, through `hostAliases`.
- **It does not hold readiness back.** A cluster is `Succeeded` without waiting for the console; floci-az logs when
  the console is available, or once when it is not after `console.ready-timeout-seconds`. Whether real ARO reports
  `Succeeded` only once its console is available was not verified.
- **amd64 only.** The OKD 4.19 console image and `quay.io/openshift/origin-console` have no arm64 build, so the
  console is off by default on an arm64 Docker daemon; set `console.image` to an arm64 build, or `console.enabled=true`
  to try the amd64 image under emulation (not tested).
- **Pages that need APIs MicroShift lacks show partial data or errors**: projects (`project.openshift.io`), cluster
  operators and settings beyond `ClusterVersion`, `Infrastructure` and `Ingress`, monitoring, dynamic plugins
  (`console.openshift.io`). Which pages are affected was not surveyed page by page.
- **Without the OAuth server** (`oauth.enabled=false`), the console has no login page: it runs with
  `--user-auth=disabled` and its ServiceAccount's token (also "DEV ONLY" upstream), bound to `cluster-admin`, so every
  visitor acts as `system:serviceaccount:openshift-console:console` (measured: a SelfSubjectReview through the
  console answers that user).

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
| `proxy-base-port` / `proxy-max-port` | `7700` / `7799` | host ports of the API proxies, one per cluster, bound by floci-az itself |
| `oauth.enabled` | `true` | `false`: new clusters get no API proxy and no OAuth server; `apiserverProfile.url` is MicroShift's published port and `oc login` fails. Clusters keep what they were created with |
| `console.enabled` | unset | unset: the console runs when the Docker daemon is amd64, and on arm64 only when `console.image` is changed. Decided when a cluster is created |
| `console.image` | `quay.io/okd/scos-content@sha256:ba8cac19...` | OKD 4.19.0-okd-scos.17's console (`oc adm release info quay.io/okd/scos-release:4.19.0-okd-scos.17 --image-for=console`), pulled by the cluster |
| `console.ready-timeout-seconds` | `600` | when floci-az logs that a console is still not available |
| `oauth.access-token-max-age-seconds` | `86400` | lifetime of the tokens the OAuth server issues ([`oauth-configuring-internal-oauth.adoc`][ocp-oauth-config]: "By default, tokens are only valid for 24 hours") |

### Persistence and restart

With a persistent storage mode (`persistent`, `hybrid`, `wal`), clusters are restored at startup: a surviving container
is adopted (the cluster stays `Succeeded`, same kubeconfig); otherwise a new container starts on the surviving data
volume (same CA, new kubeconfig after it is ready again); otherwise a new cluster starts and the log says its CA
changed. At shutdown the container and its volumes are removed unless `keep-running-on-shutdown` is set, as for AKS,
so without that flag a restart recreates the cluster. In memory mode nothing is restored. `DELETE` and
`POST /_admin/reset` remove the container and both volumes.

The API proxy comes back on the same port. Issued tokens are stored with the cluster, so they survive a restart of
floci-az while the cluster's data volume does; a cluster recreated without its volume gets a new `kubeadmin` secret
(same password) and loses its tokens. A cluster stored before the API proxy existed keeps its direct API URL and has
no OAuth server, and the log says so at startup; recreate it to get `oc login`.

---

## What is not OpenShift here

- **One node.** Masters and workers are the same MicroShift node; `masterProfile` and `workerProfiles` only shape the
  ARM resource, the node's instance-type label and `workerProfilesStatus`. There are no MachineSets, no machine API
  and no scaling.
- **OKD, not OCP.** The image is an OKD (CentOS Stream CoreOS) build of MicroShift 4.19; `ClusterVersion` reports the
  OKD version, not the `4.19.x` the ARM resource echoes. There are no cluster upgrades.
- **kubeadmin only.** `oc login -u kubeadmin` works through floci-az's OAuth server (see
  [`oc login`](#oc-login-and-the-oauth-server)); there are no other identity providers and no OAuth API objects.
- **Console on amd64, with gaps.** See [the web console](#the-web-console).
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
- Whether a real ARO API server certificate is publicly trusted, which would let `oc login` work without
  `--certificate-authority` or `--insecure-skip-tls-verify`.
- Which `oc` release first asks for a SelfSubjectReview before `users/~` (oc commit `a9e0887fbb`, 2023, about 4.15);
  both are served for kubeadmin.
- floci-az in Docker, a remote Docker daemon and Docker Desktop with the API proxy (see the table above).

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
[ocp-kubeadmin]: https://github.com/openshift/openshift-docs/blob/enterprise-4.19/modules/authentication-kubeadmin.adoc
[ocp-remove-kubeadmin]: https://github.com/openshift/openshift-docs/blob/enterprise-4.19/modules/authentication-remove-kubeadmin.adoc
[ocp-rbac]: https://github.com/openshift/openshift-docs/blob/enterprise-4.19/modules/rbac-viewing-cluster-roles.adoc
[ocp-oauth-config]: https://github.com/openshift/openshift-docs/blob/enterprise-4.19/modules/oauth-configuring-internal-oauth.adoc
[oauth-server]: https://github.com/openshift/oauth-server/tree/release-4.19
[oauth-apiserver-user]: https://github.com/openshift/oauth-apiserver/blob/release-4.19/pkg/user/apiserver/registry/user/etcd/etcd.go
[cao-metadata]: https://github.com/openshift/cluster-authentication-operator/blob/release-4.19/pkg/controllers/metadata/metadata_controller.go
[console-operator]: https://github.com/openshift/console-operator/tree/release-4.19
[aro-branding]: https://github.com/Azure/ARO-RP/blob/master/pkg/cluster/consolebranding.go
