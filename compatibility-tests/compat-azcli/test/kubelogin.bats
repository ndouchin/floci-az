#!/usr/bin/env bats
# kubelogin v0.2.22, the exec plugin of an Entra AKS cluster's user kubeconfig, against floci-az's Entra: the modes
# that need no browser get a v1.0 token for the AKS server application, the shape AKS's token webhook accepts.
# kubelogin refuses a non-HTTPS authority and, for a host outside Microsoft's, needs --disable-instance-discovery.

setup() {
    load 'test_helper/common-setup'
    local host="${FLOCI_AZ_ENDPOINT#http://}"
    host="${host#https://}"
    AUTHORITY="https://${host%/}/"
    AKS_SERVER="6dae42f8-4368-4678-94ff-3960e28e3630"
    AKS_CLIENT="80faf920-1908-4b52-b5ef-a8e7bedfc67a"
    KUBECACHEDIR="$(mktemp -d)"
    export KUBECACHEDIR
}

teardown() {
    rm -rf "$KUBECACHEDIR"
}

# The claims of the access token in an ExecCredential, as JSON.
claims() {
    local payload
    payload=$(echo "$1" | jq -r '.status.token' | cut -d. -f2 | tr '_-' '/+')
    while [ $(( ${#payload} % 4 )) -ne 0 ]; do payload="${payload}="; done
    echo "$payload" | base64 -d
}

@test "kubelogin: -l spn gets a v1.0 token for the AKS server application" {
    run kubelogin get-token -l spn --server-id "$AKS_SERVER" --client-id "$CLIENT_ID" \
        --client-secret "${AZURE_CLIENT_SECRET:-floci-az-dev-secret}" --tenant-id "$TENANT_ID" \
        --authority-host "$AUTHORITY" --disable-instance-discovery
    assert_success
    local c
    c=$(claims "$output")
    assert_equal "$(echo "$c" | jq -r '.aud')" "$AKS_SERVER"
    assert_equal "$(echo "$c" | jq -r '.ver')" "1.0"
    assert_equal "$(echo "$c" | jq -r '.idtyp')" "app"
}

@test "kubelogin: -l ropc signs the dev user in through home realm discovery" {
    run kubelogin get-token -l ropc --server-id "$AKS_SERVER" --client-id "$AKS_CLIENT" \
        --username dev-user@floci-az.local --password any --tenant-id "$TENANT_ID" \
        --authority-host "$AUTHORITY" --disable-instance-discovery
    assert_success
    local c
    c=$(claims "$output")
    assert_equal "$(echo "$c" | jq -r '.aud')" "$AKS_SERVER"
    assert_equal "$(echo "$c" | jq -r '.upn')" "dev-user@floci-az.local"
    echo "$c" | jq -e '.groups | index("44444444-4444-4444-4444-444444444444")'
}

@test "kubelogin: -l azurecli reuses the az CLI sign-in" {
    run kubelogin get-token -l azurecli --server-id "$AKS_SERVER"
    assert_success
    local c
    c=$(claims "$output")
    assert_equal "$(echo "$c" | jq -r '.aud')" "$AKS_SERVER"
    assert_equal "$(echo "$c" | jq -r '.tid')" "$TENANT_ID"
}
