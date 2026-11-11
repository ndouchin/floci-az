package io.floci.az.services.aks;

import io.floci.az.services.aks.AksModels.WebhookCertificates;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.IOException;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;

/**
 * The Workload Identity add-on: the upstream azure-workload-identity mutating webhook ({@code deploy/azure-wi-webhook.yaml}
 * at v1.6.3) in {@code kube-system}, where AKS runs it as {@code azure-wi-webhook-controller-manager}, configured by
 * {@code azure-wi-webhook-config} ({@code workload-identity-overview.md}).
 *
 * <p>The serving certificate is generated here and pre-seeded in {@code azure-wi-webhook-server-cert}, with the
 * webhook's {@code caBundle} set, so the webhook is ready in seconds instead of waiting about two minutes for
 * cert-controller to write it and for the Secret to reach the pod. The CA and certificate follow what cert-controller
 * ({@code pkg/rotator/rotator.go} at v0.16.0) creates and keeps: CA {@code CN=azure-workload-identity-ca},
 * {@code O=azure-workload-identity} with that name as a DNS SAN, a serving certificate for
 * {@code azure-wi-webhook-webhook-service.kube-system.svc}, both valid well past its 90-day lookahead.
 */
final class AksWorkloadIdentityWebhook {

    static final String MANIFEST = "floci-aks-workload-identity-webhook.yaml";
    static final String SYSTEM_LABEL = "azure-workload-identity.io/system";
    static final String CA_NAME = "azure-workload-identity-ca";
    static final String CA_ORGANIZATION = "azure-workload-identity";
    static final String SERVICE_DNS_NAME = "azure-wi-webhook-webhook-service.kube-system.svc";
    private static final SecureRandom RANDOM = new SecureRandom();

    private AksWorkloadIdentityWebhook() {
    }

    /** A CA and serving certificate cert-controller keeps, valid for ten years. */
    static WebhookCertificates certificates() {
        try {
            KeyPair caKeys = keyPair();
            KeyPair serverKeys = keyPair();
            Instant now = Instant.now().minus(1, ChronoUnit.HOURS);
            Date notAfter = Date.from(now.plus(3650, ChronoUnit.DAYS));
            X500Name caName = new X500Name("CN=" + CA_NAME + ",O=" + CA_ORGANIZATION);
            JcaX509v3CertificateBuilder ca = new JcaX509v3CertificateBuilder(caName, BigInteger.ZERO, Date.from(now),
                    notAfter, caName, caKeys.getPublic());
            ca.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
            ca.addExtension(Extension.keyUsage, true,
                    new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment | KeyUsage.keyCertSign));
            ca.addExtension(Extension.subjectAlternativeName, false,
                    new GeneralNames(new GeneralName(GeneralName.dNSName, CA_NAME)));
            X509CertificateHolder caCert = ca.build(signer(caKeys.getPrivate()));

            JcaX509v3CertificateBuilder server = new JcaX509v3CertificateBuilder(caName,
                    new BigInteger(64, RANDOM), Date.from(now), notAfter, new X500Name("CN=" + SERVICE_DNS_NAME),
                    serverKeys.getPublic());
            server.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            server.addExtension(Extension.keyUsage, true,
                    new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
            server.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
            server.addExtension(Extension.subjectAlternativeName, false,
                    new GeneralNames(new GeneralName(GeneralName.dNSName, SERVICE_DNS_NAME)));
            X509CertificateHolder serverCert = server.build(signer(caKeys.getPrivate()));
            return new WebhookCertificates(pem(caCert), pem(caKeys.getPrivate()), pem(serverCert),
                    pem(serverKeys.getPrivate()));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate the Workload Identity webhook certificates", e);
        }
    }

    /**
     * The add-on's objects, {@code deploy/azure-wi-webhook.yaml} v1.6.3 moved to {@code kube-system}.
     *
     * @param authorityHost the {@code activeDirectoryEndpoint} of an {@code AzureStackCloud} environment in "authority"
     *                      mode, which go-autorest reads from {@code AZURE_ENVIRONMENT_FILEPATH}; null to keep
     *                      {@code AzurePublicCloud}, whose authority is {@code https://login.microsoftonline.com/}
     */
    static String manifest(String image, String tenantId, WebhookCertificates certs, String authorityHost) {
        String environment = authorityHost == null ? "AzurePublicCloud" : "AzureStackCloud";
        StringBuilder yaml = new StringBuilder(RBAC.formatted(SYSTEM_LABEL));
        yaml.append("""
                ---
                apiVersion: v1
                kind: ConfigMap
                metadata:
                  labels:
                    %1$s: "true"
                  name: azure-wi-webhook-config
                  namespace: kube-system
                data:
                  AZURE_ENVIRONMENT: %2$s
                  AZURE_TENANT_ID: "%3$s"
                """.formatted(SYSTEM_LABEL, environment, tenantId));
        if (authorityHost != null) {
            yaml.append("  AZURE_ENVIRONMENT_FILEPATH: /etc/azure-env/env.json\n");
            yaml.append("""
                    ---
                    apiVersion: v1
                    kind: ConfigMap
                    metadata:
                      labels:
                        %1$s: "true"
                      name: azure-wi-webhook-environment
                      namespace: kube-system
                    data:
                      env.json: '{"name": "AzureStackCloud", "activeDirectoryEndpoint": "%2$s"}'
                    """.formatted(SYSTEM_LABEL, authorityHost));
        }
        yaml.append("""
                ---
                apiVersion: v1
                kind: Secret
                metadata:
                  labels:
                    %1$s: "true"
                  name: azure-wi-webhook-server-cert
                  namespace: kube-system
                type: Opaque
                data:
                  ca.crt: %2$s
                  ca.key: %3$s
                  tls.crt: %4$s
                  tls.key: %5$s
                """.formatted(SYSTEM_LABEL, b64(certs.caCert()), b64(certs.caKey()), b64(certs.tlsCert()),
                b64(certs.tlsKey())));
        yaml.append(DEPLOYMENT.formatted(SYSTEM_LABEL, image,
                authorityHost == null ? "" : ENVIRONMENT_MOUNT, authorityHost == null ? "" : ENVIRONMENT_VOLUME));
        yaml.append(WEBHOOK.formatted(SYSTEM_LABEL, b64(certs.caCert())));
        return yaml.toString();
    }

    private static final String RBAC = """
            apiVersion: v1
            kind: ServiceAccount
            metadata:
              labels:
                %1$s: "true"
              name: azure-wi-webhook-admin
              namespace: kube-system
            ---
            apiVersion: rbac.authorization.k8s.io/v1
            kind: Role
            metadata:
              labels:
                %1$s: "true"
              name: azure-wi-webhook-manager-role
              namespace: kube-system
            rules:
            - apiGroups: [""]
              resources: [secrets]
              verbs: [create, delete, get, list, patch, update, watch]
            ---
            apiVersion: rbac.authorization.k8s.io/v1
            kind: ClusterRole
            metadata:
              labels:
                %1$s: "true"
              name: azure-wi-webhook-manager-role
            rules:
            - apiGroups: [""]
              resources: [serviceaccounts]
              verbs: [get, list, watch]
            - apiGroups: [admissionregistration.k8s.io]
              resources: [mutatingwebhookconfigurations]
              verbs: [get, list, update, watch]
            ---
            apiVersion: rbac.authorization.k8s.io/v1
            kind: RoleBinding
            metadata:
              labels:
                %1$s: "true"
              name: azure-wi-webhook-manager-rolebinding
              namespace: kube-system
            roleRef:
              apiGroup: rbac.authorization.k8s.io
              kind: Role
              name: azure-wi-webhook-manager-role
            subjects:
            - kind: ServiceAccount
              name: azure-wi-webhook-admin
              namespace: kube-system
            ---
            apiVersion: rbac.authorization.k8s.io/v1
            kind: ClusterRoleBinding
            metadata:
              labels:
                %1$s: "true"
              name: azure-wi-webhook-manager-rolebinding
            roleRef:
              apiGroup: rbac.authorization.k8s.io
              kind: ClusterRole
              name: azure-wi-webhook-manager-role
            subjects:
            - kind: ServiceAccount
              name: azure-wi-webhook-admin
              namespace: kube-system
            ---
            apiVersion: v1
            kind: Service
            metadata:
              labels:
                %1$s: "true"
              name: azure-wi-webhook-webhook-service
              namespace: kube-system
            spec:
              ports:
              - port: 443
                targetPort: 9443
              selector:
                %1$s: "true"
            """;

    private static final String DEPLOYMENT = """
            ---
            apiVersion: apps/v1
            kind: Deployment
            metadata:
              labels:
                %1$s: "true"
              name: azure-wi-webhook-controller-manager
              namespace: kube-system
            spec:
              replicas: 2
              selector:
                matchLabels:
                  %1$s: "true"
              template:
                metadata:
                  labels:
                    %1$s: "true"
                spec:
                  containers:
                  - args:
                    - --log-level=info
                    command:
                    - /manager
                    env:
                    - name: POD_NAMESPACE
                      valueFrom:
                        fieldRef:
                          apiVersion: v1
                          fieldPath: metadata.namespace
                    envFrom:
                    - configMapRef:
                        name: azure-wi-webhook-config
                    image: %2$s
                    imagePullPolicy: IfNotPresent
                    livenessProbe:
                      failureThreshold: 6
                      httpGet:
                        path: /healthz
                        port: healthz
                      initialDelaySeconds: 15
                      periodSeconds: 20
                    name: manager
                    ports:
                    - containerPort: 9443
                      name: webhook-server
                      protocol: TCP
                    - containerPort: 8095
                      name: metrics
                      protocol: TCP
                    - containerPort: 9440
                      name: healthz
                      protocol: TCP
                    readinessProbe:
                      httpGet:
                        path: /readyz
                        port: healthz
                      initialDelaySeconds: 5
                      periodSeconds: 5
                    resources:
                      limits:
                        cpu: 100m
                        memory: 30Mi
                      requests:
                        cpu: 100m
                        memory: 20Mi
                    securityContext:
                      allowPrivilegeEscalation: false
                      capabilities:
                        drop:
                        - ALL
                      readOnlyRootFilesystem: true
                      runAsGroup: 65532
                      runAsNonRoot: true
                      runAsUser: 65532
                      seccompProfile:
                        type: RuntimeDefault
                    volumeMounts:
                    - mountPath: /certs
                      name: cert
                      readOnly: true
            %3$s      nodeSelector:
                    kubernetes.io/os: linux
                  priorityClassName: system-cluster-critical
                  serviceAccountName: azure-wi-webhook-admin
                  volumes:
                  - name: cert
                    secret:
                      defaultMode: 420
                      secretName: azure-wi-webhook-server-cert
            %4$s---
            apiVersion: policy/v1
            kind: PodDisruptionBudget
            metadata:
              labels:
                %1$s: "true"
              name: azure-wi-webhook-controller-manager
              namespace: kube-system
            spec:
              minAvailable: 1
              selector:
                matchLabels:
                  %1$s: "true"
            """;

    private static final String ENVIRONMENT_MOUNT = """
                    - mountPath: /etc/azure-env
                      name: environment
                      readOnly: true
            """;

    private static final String ENVIRONMENT_VOLUME = """
                  - name: environment
                    configMap:
                      name: azure-wi-webhook-environment
            """;

    private static final String WEBHOOK = """
            ---
            apiVersion: admissionregistration.k8s.io/v1
            kind: MutatingWebhookConfiguration
            metadata:
              labels:
                %1$s: "true"
              name: azure-wi-webhook-mutating-webhook-configuration
            webhooks:
            - admissionReviewVersions:
              - v1
              - v1beta1
              clientConfig:
                caBundle: %2$s
                service:
                  name: azure-wi-webhook-webhook-service
                  namespace: kube-system
                  path: /mutate-v1-pod
              failurePolicy: Fail
              matchPolicy: Equivalent
              name: mutation.azure-workload-identity.io
              objectSelector:
                matchLabels:
                  azure.workload.identity/use: "true"
              reinvocationPolicy: IfNeeded
              rules:
              - apiGroups:
                - ""
                apiVersions:
                - v1
                operations:
                - CREATE
                resources:
                - pods
              sideEffects: None
            """;

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static ContentSigner signer(PrivateKey key) throws Exception {
        return new JcaContentSignerBuilder("SHA256WithRSA").build(key);
    }

    private static String pem(Object object) throws IOException {
        StringWriter out = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(out)) {
            writer.writeObject(object);
        }
        return out.toString();
    }

    private static String b64(String pem) {
        return Base64.getEncoder().encodeToString(pem.getBytes(StandardCharsets.UTF_8));
    }
}
