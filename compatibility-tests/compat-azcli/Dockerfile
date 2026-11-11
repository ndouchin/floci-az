FROM mcr.microsoft.com/azure-cli:latest

# Azure Linux (Mariner) base — bash/curl/jq/openssl ship preinstalled; add git + socat.
RUN tdnf install -y git socat ca-certificates unzip && tdnf clean all

# kubelogin, the exec plugin of Entra AKS kubeconfigs, for test/kubelogin.bats.
ARG KUBELOGIN_VERSION=v0.2.22
RUN arch="$(uname -m)"; case "$arch" in x86_64) arch=amd64 ;; aarch64) arch=arm64 ;; esac; \
    curl -fsSL -o /tmp/kubelogin.zip \
      "https://github.com/Azure/kubelogin/releases/download/${KUBELOGIN_VERSION}/kubelogin-linux-${arch}.zip" \
    && unzip -q /tmp/kubelogin.zip -d /tmp/kubelogin \
    && install -m 0755 "/tmp/kubelogin/bin/linux_${arch}/kubelogin" /usr/local/bin/kubelogin \
    && rm -rf /tmp/kubelogin /tmp/kubelogin.zip

WORKDIR /app

RUN git clone --depth 1 https://github.com/bats-core/bats-core.git /opt/bats-core \
    && git clone --depth 1 https://github.com/bats-core/bats-support.git /opt/bats-support \
    && git clone --depth 1 https://github.com/bats-core/bats-assert.git /opt/bats-assert

COPY . .

ENV FLOCI_AZ_ENDPOINT=http://floci-az:4577
ENV BATS_LIB_PATH=/opt
ENV AZURE_SUBSCRIPTION_ID=00000000-0000-0000-0000-000000000001
ENV AZURE_TENANT_ID=00000000-0000-0000-0000-000000000002
ENV AZURE_CLIENT_ID=11111111-1111-1111-1111-111111111111
ENV AZURE_CLIENT_SECRET=floci-az-dev-secret

RUN chmod +x /app/run-bats-in-container.sh && mkdir -p /results

ENTRYPOINT ["./run-bats-in-container.sh"]
