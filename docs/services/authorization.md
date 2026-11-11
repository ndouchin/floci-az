# Azure RBAC (role definitions and role assignments)

floci-az serves the role part of `Microsoft.Authorization`: role definitions and role assignments, at every ARM
scope, with the built-in roles AKS's Azure RBAC uses. Azure Kubernetes Service's authorization webhook evaluates
these assignments (see [AKS: Entra ID and Azure RBAC](aks.md)); ARM requests themselves are not authorized.

Shapes follow the authorization REST spec `2022-04-01`
([`authorization-RoleAssignmentsCalls.json`](https://github.com/Azure/azure-rest-api-specs/blob/5da82d5c3687ac3cc845330aaf0f13d3a40ce47e/specification/authorization/resource-manager/Microsoft.Authorization/Authorization/stable/2022-04-01/authorization-RoleAssignmentsCalls.json));
any `api-version` is accepted.

## Endpoints

`{scope}` is any ARM scope: the tenant root (empty), `/subscriptions/{sub}`, a resource group, a resource, or a
resource's virtual child such as an AKS namespace (`<clusterId>/namespaces/<name>`).

| Path | Methods | Notes |
|---|---|---|
| `{scope}/providers/Microsoft.Authorization/roleDefinitions` | GET | `$filter=roleName eq '<name>'` (what `az role assignment create --role <name>` and azurerm send) and `type eq 'BuiltInRole'` or `'CustomRole'` |
| `{scope}/providers/Microsoft.Authorization/roleDefinitions/{guid}` | GET, PUT, DELETE | PUT and DELETE for custom roles only |
| `{scope}/providers/Microsoft.Authorization/roleAssignments` | GET | `$filter=atScope()`, `principalId eq '<id>'`, `assignedTo('<id>')`, and `atScope() and ...` |
| `{scope}/providers/Microsoft.Authorization/roleAssignments/{guid}` | GET, PUT, DELETE | |

## Built-in roles

The catalog `src/main/resources/azure/built-in-roles.json` holds the JSON definitions of these roles, copied
verbatim from the MicrosoftDocs built-in role pages
([containers](https://learn.microsoft.com/en-us/azure/role-based-access-control/built-in-roles/containers),
[general](https://learn.microsoft.com/en-us/azure/role-based-access-control/built-in-roles/general),
[privileged](https://learn.microsoft.com/en-us/azure/role-based-access-control/built-in-roles/privileged)) by
`tools/authorization/gen_builtin_roles.py`:

| Role | Id |
|---|---|
| Azure Kubernetes Service Cluster Admin Role | `0ab0b1a8-8aac-4efd-b8c2-3ee1fb270be8` |
| Azure Kubernetes Service Cluster User Role | `4abbcc35-e782-43d8-92c5-2d3f1bd2253f` |
| Azure Kubernetes Service RBAC Admin | `3498e952-d568-435e-9b2c-8d77e338d7f7` |
| Azure Kubernetes Service RBAC Cluster Admin | `b1ff04bb-8a4e-4dc4-8eb5-8693973ce19b` |
| Azure Kubernetes Service RBAC Reader | `7f6c6a51-bcf8-42ba-9220-52d62157d7db` |
| Azure Kubernetes Service RBAC Writer | `a7ffa36f-339b-4b5c-8bdf-e2c188b2c0eb` |
| Contributor | `b24988ac-6180-42a0-ab88-20f7382dd24c` |
| Owner | `8e3af657-a8ff-443c-a75c-2fe8c4bcb635` |
| Reader | `acdd72a7-3385-48ef-bd42-f606fba81ae7` |

A built-in role answers under the subscription the request names (`/subscriptions/{sub}/providers/...`), or at the
tenant root when it names none. Other built-in roles are not seeded: a lookup by name returns an empty list.

## Behavior

| Request | Answer |
|---|---|
| PUT of an assignment | 201 with `properties.scope` set to the scope of the path |
| Assignment name that is not a GUID | 400 `InvalidRoleAssignmentId`: "The role assignment ID '<name>' is not valid. The role assignment ID must be a GUID." |
| Same principal, role and scope under another name | 409 `RoleAssignmentExists`: "The role assignment already exists." ([azure-cli](https://github.com/Azure/azure-cli/blob/51552fd56854d33f646a031bbbf024cf1fbc4d42/src/azure-cli/azure/cli/command_modules/role/custom.py) catches this code) |
| PUT of an existing name with another principal, role or scope | 400 `RoleAssignmentUpdateNotPermitted` ([troubleshooting](https://learn.microsoft.com/en-us/azure/role-based-access-control/troubleshooting)) |
| `roleDefinitionId` naming no role | 400 `RoleDefinitionDoesNotExist` |
| GET of a missing assignment | 404 `RoleAssignmentNotFound` |
| DELETE | 200 with the deleted assignment; 204 when there was none |
| Custom role with the name of another custom role | 409 `RoleDefinitionWithSameNameExists` |
| DELETE of a custom role that is still assigned | 409 `RoleDefinitionHasAssignments` |

List semantics: without a filter, a list returns the assignments at, above and below the scope; `atScope()` keeps
those at or above it; `principalId eq` keeps one principal; `assignedTo()` keeps that principal and the groups it is
a direct member of in the Entra directory.

Evaluation ([role definitions](https://learn.microsoft.com/en-us/azure/role-based-access-control/role-definitions)):
an assignment applies at its scope and below; `*` matches any characters; a permission block grants what its
`actions` (or `dataActions`) match minus what its `notActions` (or `notDataActions`) match, and the result is the
union over every assignment, so one role's exclusions never take away what another role grants.

## Not emulated

- `PrincipalNotFound`: the principal id is not checked against the directory, so assignments for identities the
  emulator does not know (and the replication delay real Azure has) both work.
- ABAC conditions (`condition`, `conditionVersion`) are stored and returned, never evaluated. Deny assignments,
  classic administrators and eligible (PIM) assignments are not served.
- Management-group scopes are not walked: an assignment at a management group does not apply to its subscriptions.
- Assignments do not authorize ARM requests; only AKS's Azure RBAC webhook evaluates them.
- Not verified against real Azure: the error codes for a non-GUID role definition name (emulator:
  `InvalidRoleDefinitionId`), for changing or deleting a built-in role (emulator: 403 `AuthorizationFailed`), for a
  body without `principalId` or `roleDefinitionId` (emulator: 400 `InvalidRequestContent`), the message of
  `RoleDefinitionHasAssignments`, and whether a repeated identical PUT answers 200 or 201 (emulator: 201).

## Configuration

| Key | Default | Meaning |
|---|---|---|
| `floci-az.services.authorization.enabled` | `true` | Serve role definitions and role assignments |
