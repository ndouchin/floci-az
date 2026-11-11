#!/usr/bin/env python3
"""Regenerate src/main/resources/azure/built-in-roles.json from the MicrosoftDocs built-in role pages.

`io.floci.az.services.authorization.RoleDefinitions` reads this file for the built-in role definitions it serves
under Microsoft.Authorization/roleDefinitions and evaluates in Azure RBAC checks. Nothing in the file is hand-typed:
each page of `MicrosoftDocs/azure-docs` `articles/role-based-access-control/built-in-roles/` ends every role section
with the role's JSON definition, and this script copies the blocks of the roles in `ROLES` verbatim.

Run from anywhere in the repo (needs network access to raw.githubusercontent.com):
    python3 tools/authorization/gen_builtin_roles.py
"""
from __future__ import annotations

import json
import re
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "src/main/resources/azure/built-in-roles.json"
COMMIT = "99f04393e8b8c1d2ae086627cb48465800060447"
BASE = f"https://raw.githubusercontent.com/MicrosoftDocs/azure-docs/{COMMIT}/articles/role-based-access-control/built-in-roles/"
PAGES = ["containers.md", "general.md", "privileged.md"]
ROLES = {
    "Azure Kubernetes Service Cluster Admin Role",
    "Azure Kubernetes Service Cluster User Role",
    "Azure Kubernetes Service RBAC Admin",
    "Azure Kubernetes Service RBAC Cluster Admin",
    "Azure Kubernetes Service RBAC Reader",
    "Azure Kubernetes Service RBAC Writer",
    "Contributor",
    "Owner",
    "Reader",
}


def main() -> None:
    roles = []
    for page in PAGES:
        with urllib.request.urlopen(BASE + page) as response:
            text = response.read().decode("utf-8")
        for section in re.split(r"\n## ", text):
            name = section.split("\n", 1)[0].strip()
            if name in ROLES:
                block = re.search(r"```json\n(.*?)\n```", section, re.S)
                if block is None:
                    raise SystemExit(f"{page}: no JSON definition under '{name}'")
                roles.append(json.loads(block.group(1)))
    missing = ROLES - {role["roleName"] for role in roles}
    if missing:
        raise SystemExit(f"roles not found: {sorted(missing)}")
    roles.sort(key=lambda role: role["roleName"])
    document = {
        "_source": f"MicrosoftDocs/azure-docs@{COMMIT} articles/role-based-access-control/built-in-roles/"
                   "{containers,general,privileged}.md, JSON blocks copied verbatim",
        "roleDefinitions": roles,
    }
    OUTPUT.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {len(roles)} roles to {OUTPUT.relative_to(REPO_ROOT)}")


if __name__ == "__main__":
    main()
